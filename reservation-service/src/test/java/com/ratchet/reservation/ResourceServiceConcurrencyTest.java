package com.ratchet.reservation;

import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.exception.InsufficientAvailabilityException;
import com.ratchet.reservation.repository.ResourceRepository;
import com.ratchet.reservation.service.ResourceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ResourceServiceConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private ResourceRepository resourceRepository;

    private UUID resourceId;

    @BeforeEach
    void setUp() {
        resourceRepository.deleteAll();
        Resource resource = new Resource();
        resourceId = UUID.randomUUID();
        resource.setId(resourceId);
        resource.setAvailableUnits(1);
        resourceRepository.save(resource);
    }

    @AfterEach
    void tearDown() {
        resourceRepository.deleteAll();
    }

    @RepeatedTest(5)
    void testConcurrentHold() throws Exception {
        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger optimisticLockExceptionCount = new AtomicInteger(0);
        AtomicInteger insufficientAvailabilityExceptionCount = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                latch.await();
                resourceService.hold(resourceId, "holder-ref-" + Thread.currentThread().getId());
                successCount.incrementAndGet();
            } catch (ObjectOptimisticLockingFailureException e) {
                optimisticLockExceptionCount.incrementAndGet();
            } catch (InsufficientAvailabilityException e) {
                insufficientAvailabilityExceptionCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        executorService.submit(task);
        executorService.submit(task);

        // start both threads
        latch.countDown();

        // wait for both to finish
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        executorService.shutdown();

        // Verifications
        // Exactly one thread should succeed
        assertThat(successCount.get()).isEqualTo(1);

        // Exactly one thread should fail with either OptimisticLock or InsufficientAvailability
        assertThat(optimisticLockExceptionCount.get() + insufficientAvailabilityExceptionCount.get()).isEqualTo(1);

        // Verify the database state
        Resource updatedResource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(updatedResource.getAvailableUnits()).isEqualTo(0);
    }
}
