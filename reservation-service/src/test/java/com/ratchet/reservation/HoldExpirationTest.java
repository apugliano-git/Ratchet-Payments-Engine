package com.ratchet.reservation;

import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.repository.ResourceRepository;
import com.ratchet.reservation.service.HoldService;
import com.ratchet.reservation.service.HoldSweepScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class HoldExpirationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--notify-keyspace-events", "Ex");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private HoldService holdService;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private HoldSweepScheduler sweepScheduler;

    private UUID resourceId;

    @BeforeEach
    void setUp() {
        holdRepository.deleteAll();
        resourceRepository.deleteAll();

        Resource resource = new Resource();
        resourceId = UUID.randomUUID();
        resource.setId(resourceId);
        resource.setAvailableUnits(5);
        resourceRepository.save(resource);
    }

    @AfterEach
    void tearDown() {
        holdRepository.deleteAll();
        resourceRepository.deleteAll();
    }

    @Test
    void testRedisExpirationEventTriggersExpire() throws Exception {
        UUID holdId = holdService.hold(resourceId, "holder-1", Duration.ofSeconds(2));

        // The hold should be ACTIVE initially
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);

        // Wait for Redis to expire the key and trigger the listener
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Hold expiredHold = holdRepository.findById(holdId).orElseThrow();
            assertThat(expiredHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        });

        // Verify resource units are restored
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(5);
    }

    @Test
    void testSweepSchedulerExpiresOrphanedHolds() {
        // Manually create an orphaned hold in the database without going through holdService 
        // to simulate a Redis write failure or dropped event
        Hold hold = new Hold();
        hold.setId(UUID.randomUUID());
        hold.setResourceId(resourceId);
        hold.setHolderRef("orphaned-holder");
        hold.setStatus(HoldStatus.ACTIVE);
        // Set it as expired in the past
        hold.setCreatedAt(Instant.now().minusSeconds(120));
        hold.setExpiresAt(Instant.now().minusSeconds(60));
        holdRepository.save(hold);

        // Reduce resource count manually to match the hold creation
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        resource.setAvailableUnits(4);
        resourceRepository.save(resource);

        // Trigger the sweep manually
        sweepScheduler.sweepExpiredHolds();

        // Verify it was swept
        Hold sweptHold = holdRepository.findById(hold.getId()).orElseThrow();
        assertThat(sweptHold.getStatus()).isEqualTo(HoldStatus.EXPIRED);

        // Verify resource units are restored
        Resource restoredResource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(restoredResource.getAvailableUnits()).isEqualTo(5);
    }

    @org.junit.jupiter.api.RepeatedTest(5)
    void testRaceConditionBetweenListenerAndSweep() throws Exception {
        // We create the hold in Postgres but we will force manual expiration
        UUID holdId = holdService.hold(resourceId, "holder-race", Duration.ofSeconds(10));

        int threadCount = 2;
        java.util.concurrent.ExecutorService executorService = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        java.util.concurrent.atomic.AtomicInteger optimisticLockExceptions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger invalidStateExceptions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    latch.await();
                    holdService.expire(holdId);
                    successCount.incrementAndGet();
                } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                    optimisticLockExceptions.incrementAndGet();
                } catch (com.ratchet.reservation.exception.InvalidHoldStateException e) {
                    invalidStateExceptions.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // start both threads simultaneously
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executorService.shutdown();

        System.out.println("Race Condition Result:");
        System.out.println("  Success: " + successCount.get());
        System.out.println("  InvalidState: " + invalidStateExceptions.get());
        System.out.println("  OptimisticLock: " + optimisticLockExceptions.get());

        // Verify resource units are exactly 5 (4 after hold, +1 after expire). 
        // It must NOT be 6.
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(5);
        
        // One should succeed, one should fail
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(optimisticLockExceptions.get() + invalidStateExceptions.get()).isEqualTo(1);
    }
}
