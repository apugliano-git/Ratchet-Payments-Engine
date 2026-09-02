package com.ratchet.reservation.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.domain.OutboxEvent;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.repository.OutboxRepository;
import com.ratchet.reservation.repository.ResourceRepository;
import com.ratchet.reservation.scheduler.OutboxPublisherScheduler;
import com.ratchet.reservation.service.HoldService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@EmbeddedKafka(partitions = 1, brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" }, topics = {"reservation.events.v1"})
public class OutboxIntegrationTest {

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
    }

    @Autowired
    private HoldService holdService;

    @Autowired
    private ResourceRepository resourceRepository;

    @Autowired
    private HoldRepository holdRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisherScheduler scheduler;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID resourceId;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        holdRepository.deleteAll();
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
        holdRepository.deleteAll();
        resourceRepository.deleteAll();
    }

    @Test
    void testConfirmHoldCreatesOutboxEventAndSchedulerPublishesIt() {
        // 1. Hold and Confirm
        UUID holdId = holdService.hold(resourceId, "user-123", Duration.ofMinutes(5));
        holdService.confirm(holdId);

        // Verify OutboxEvent is created
        List<OutboxEvent> events = outboxRepository.findByProcessedAtIsNull();
        assertThat(events).hasSize(2);
        OutboxEvent event = events.stream()
                .filter(candidate -> candidate.getEventType().equals("RESERVATION_CONFIRMED"))
                .findFirst().orElseThrow();
        assertThat(event.getAggregateType()).isEqualTo("Hold");
        assertThat(event.getAggregateId()).isEqualTo(holdId);
        assertThat(event.getResourceId()).isEqualTo(resourceId);
        assertThat(event.getEventType()).isEqualTo("RESERVATION_CONFIRMED");
        assertThat(event.getProcessedAt()).isNull();
        assertThat(event.getPayload()).contains(holdId.toString());
        assertThat(event.getPayload()).contains("user-123");
        assertThat(event.getPayload()).contains("eventId");

        // Set up Kafka consumer
        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("testGroup", "true", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> cf = new DefaultKafkaConsumerFactory<>(consumerProps, new StringDeserializer(), new StringDeserializer());
        Consumer<String, String> consumer = cf.createConsumer();
        embeddedKafkaBroker.consumeFromAllEmbeddedTopics(consumer);

        // 2. Run Scheduler manually
        scheduler.publishOutboxEvents();

        // Verify event is published
        ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
        assertThat(records.count()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (ConsumerRecord<String, String> record : records) {
            if (record.key().equals(resourceId.toString()) && record.value().contains("RESERVATION_CONFIRMED")) {
                assertThat(record.value()).contains("user-123");
                assertThat(record.value()).contains("\"eventVersion\":1");
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();

        consumer.close();

        // Verify OutboxEvent is updated
        OutboxEvent updatedEvent = outboxRepository.findById(event.getId()).orElseThrow();
        assertThat(updatedEvent.getProcessedAt()).isNotNull();
    }

    @Test
    void testPublisherUsesDeterministicOutboxOrder() throws Exception {
        UUID first = holdService.hold(resourceId, "first", Duration.ofMinutes(5));
        holdService.confirm(first);
        UUID second = holdService.hold(resourceId, "second", Duration.ofMinutes(5));

        List<OutboxEvent> pending = outboxRepository.findByProcessedAtIsNullOrderByCreatedAtAscIdAsc();
        assertThat(pending).extracting(OutboxEvent::getEventType)
                .containsExactly("RESERVATION_HOLD_CREATED", "RESERVATION_CONFIRMED", "RESERVATION_HOLD_CREATED");
        assertThat(pending).allSatisfy(event -> assertThat(event.getResourceId()).isEqualTo(resourceId));

        Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("order-" + UUID.randomUUID(), "false", embeddedKafkaBroker);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer());
        try (Consumer<String, String> consumer = consumerFactory.createConsumer()) {
            embeddedKafkaBroker.consumeFromAllEmbeddedTopics(consumer);
            scheduler.publishOutboxEvents();

            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10));
            List<String> publishedTypes = new ArrayList<>();
            for (ConsumerRecord<String, String> record : records) {
                com.fasterxml.jackson.databind.JsonNode envelope = objectMapper.readTree(record.value());
                String holdId = envelope.path("payload").path("holdId").asText();
                if (first.toString().equals(holdId) || second.toString().equals(holdId)) {
                    assertThat(record.key()).isEqualTo(resourceId.toString());
                    publishedTypes.add(envelope.get("eventType").asText());
                }
            }
            assertThat(publishedTypes).containsExactly(
                    "RESERVATION_HOLD_CREATED", "RESERVATION_CONFIRMED", "RESERVATION_HOLD_CREATED");
        }
    }
}
