package com.ratchet.reservation.outbox;

import com.ratchet.reservation.domain.OutboxEvent;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.repository.OutboxRepository;
import com.ratchet.reservation.repository.ResourceRepository;
import com.ratchet.reservation.scheduler.OutboxPublisherScheduler;
import com.ratchet.reservation.service.HoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
public class OutboxFailureIntegrationTest {

    @Container
    static org.testcontainers.containers.GenericContainer<?> redis = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("ratchet_db")
            .withUsername("ratchet")
            .withPassword("ratchet_password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:12345"); // Invalid broker port
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "1000"); // Fail fast
    }

    @Autowired
    private HoldService holdService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisherScheduler scheduler;

    private UUID resourceId;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        resourceRepository.deleteAll();

        Resource resource = new Resource();
        resourceId = UUID.randomUUID();
        resource.setId(resourceId);
        resource.setAvailableUnits(10);
        resourceRepository.save(resource);
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        resourceRepository.deleteAll();
    }

    @Test
    void testSchedulerFailsGracefullyWhenKafkaUnavailable() {
        // 1. Create a pending event
        UUID holdId = holdService.hold(resourceId, "user-456", Duration.ofMinutes(5));
        holdService.confirm(holdId);

        List<OutboxEvent> events = outboxRepository.findByProcessedAtIsNull();
        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);

        // 2. Run scheduler manually. It should fail to send and not throw an unhandled exception
        scheduler.publishOutboxEvents();

        // 3. Verify event is still unprocessed
        OutboxEvent unchangedEvent = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(unchangedEvent.getProcessedAt()).isNull();
    }
}
