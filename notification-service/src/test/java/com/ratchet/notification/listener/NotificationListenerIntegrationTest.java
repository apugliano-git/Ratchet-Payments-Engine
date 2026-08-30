package com.ratchet.notification.listener;

import com.ratchet.notification.domain.NotificationLog;
import com.ratchet.notification.repository.NotificationLogRepository;
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
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
public class NotificationListenerIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("notification_db")
            .withUsername("ratchet")
            .withPassword("ratchet");

    @Container
    @SuppressWarnings("resource")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "notification-service-group-test");
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    @Autowired
    private NotificationLogRepository repository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void shouldProcessHoldConfirmedEventSuccessfully() {
        String eventId = UUID.randomUUID().toString();
        UUID holdId = UUID.randomUUID();
        String payload = String.format("""
                {
                  "eventId": "%s",
                  "holdId": "%s",
                  "resourceId": "b3e0c031-1b91-4c6e-8260-1234567890ab",
                  "holderRef": "user-123",
                  "confirmedAt": "2023-10-01T12:00:00Z"
                }
                """, eventId, holdId);

        kafkaTemplate.send("reservation.hold.confirmed", holdId.toString(), payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<NotificationLog> logs = repository.findAll();
            assertThat(logs).hasSize(1);
            assertThat(logs.get(0).getEventId()).isEqualTo(eventId);
            assertThat(logs.get(0).getHoldId()).isEqualTo(holdId);
            assertThat(logs.get(0).getHolderRef()).isEqualTo("user-123");
        });
    }

    @Test
    void shouldDeduplicateIdenticalEvents() throws Exception {
        String eventId = UUID.randomUUID().toString();
        UUID holdId = UUID.randomUUID();
        String payload = String.format("""
                {
                  "eventId": "%s",
                  "holdId": "%s",
                  "resourceId": "b3e0c031-1b91-4c6e-8260-1234567890ab",
                  "holderRef": "user-123",
                  "confirmedAt": "2023-10-01T12:00:00Z"
                }
                """, eventId, holdId);

        // Send twice
        kafkaTemplate.send("reservation.hold.confirmed", holdId.toString(), payload);
        kafkaTemplate.send("reservation.hold.confirmed", holdId.toString(), payload);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(repository.count()).isEqualTo(1);
        });

        // Sleep to ensure no other logs are inserted
        Thread.sleep(2000);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void shouldIgnoreMalformedEvent() throws InterruptedException {
        String payload = """
                {
                  "holdId": "a3e0c031-1b91-4c6e-8260-1234567890ab",
                  "holderRef": "user-123"
                  // missing eventId entirely
                }
                """;

        kafkaTemplate.send("reservation.hold.confirmed", "a3e0c031-1b91-4c6e-8260-1234567890ab", payload);

        // Wait to see if anything is saved
        Thread.sleep(3000);
        assertThat(repository.count()).isEqualTo(0);
    }

    @Test
    void shouldIgnoreEventMissingHoldIdOrHolderRef() throws InterruptedException {
        String eventId = UUID.randomUUID().toString();
        String payload = String.format("""
                {
                  "eventId": "%s",
                  "resourceId": "b3e0c031-1b91-4c6e-8260-1234567890ab",
                  "confirmedAt": "2023-10-01T12:00:00Z"
                }
                """, eventId);

        kafkaTemplate.send("reservation.hold.confirmed", "a3e0c031-1b91-4c6e-8260-1234567890ab", payload);

        // Wait to see if anything is saved
        Thread.sleep(3000);
        assertThat(repository.count()).isEqualTo(0);
    }
}
