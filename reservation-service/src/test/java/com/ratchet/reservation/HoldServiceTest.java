package com.ratchet.reservation;

import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.domain.OutboxEvent;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.exception.InvalidHoldStateException;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.repository.OutboxRepository;
import com.ratchet.reservation.repository.ResourceRepository;
import com.ratchet.reservation.service.HoldService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class HoldServiceTest {


    @Container
    static org.testcontainers.containers.GenericContainer<?> redis = new org.testcontainers.containers.GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

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

    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private UUID resourceId;

    @BeforeEach
    void setUp() throws Exception {
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
    void shouldCreateHoldAndDecrementResource() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.getHolderRef()).isEqualTo("ref-123");
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(9);

        assertThat(outboxRepository.findAll()).singleElement()
                .extracting(OutboxEvent::getEventType)
                .isEqualTo("RESERVATION_HOLD_CREATED");
    }

    @Test
    void shouldReleaseHoldAndIncrementResource() throws Exception {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.release(holdId);
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.RELEASED);
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(10);

        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder("RESERVATION_HOLD_CREATED", "RESERVATION_RELEASED");
        assertCanonicalEvent(outboxRepository.findAll().stream()
                .filter(event -> "RESERVATION_RELEASED".equals(event.getEventType()))
                .findFirst().orElseThrow(), "RESERVATION_RELEASED", holdId, "ref-123");
    }

    @Test
    void shouldConfirmHoldAndNotIncrementResource() throws Exception {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.confirm(holdId);
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.CONFIRMED);
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(9);

        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder("RESERVATION_HOLD_CREATED", "RESERVATION_CONFIRMED");
        assertCanonicalEvent(outboxRepository.findAll().stream()
                .filter(event -> "RESERVATION_CONFIRMED".equals(event.getEventType()))
                .findFirst().orElseThrow(), "RESERVATION_CONFIRMED", holdId, "ref-123");
    }

    @Test
    void shouldExpireHoldAndIncrementResource() throws Exception {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.expire(holdId);
        
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.EXPIRED);
        
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(10);

        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder("RESERVATION_HOLD_CREATED", "RESERVATION_EXPIRED");
        com.fasterxml.jackson.databind.JsonNode expiredEvent = assertCanonicalEvent(outboxRepository.findAll().stream()
                .filter(event -> "RESERVATION_EXPIRED".equals(event.getEventType()))
                .findFirst().orElseThrow(), "RESERVATION_EXPIRED", holdId, "ref-123");
        assertThat(expiredEvent.get("payload").get("scheduledExpiresAt").asText())
                .isEqualTo(hold.getExpiresAt().toString());
    }

    @Test
    void shouldThrowWhenInvalidStateTransition() {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        holdService.confirm(holdId); // Status is now CONFIRMED
        
        assertThatThrownBy(() -> holdService.release(holdId))
                .isInstanceOf(InvalidHoldStateException.class);
                
        assertThatThrownBy(() -> holdService.confirm(holdId))
                .isInstanceOf(InvalidHoldStateException.class);
                
        assertThatThrownBy(() -> holdService.expire(holdId))
                .isInstanceOf(InvalidHoldStateException.class);
                
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        assertThat(resource.getAvailableUnits()).isEqualTo(9); // Unchanged
        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder("RESERVATION_HOLD_CREATED", "RESERVATION_CONFIRMED");
    }

    @Test
    void shouldNotCreateEventsForRepeatedSuccessfulTransitions() {
        UUID releasedHold = holdService.hold(resourceId, "ref-release", Duration.ofMinutes(15));
        holdService.release(releasedHold);
        assertThatThrownBy(() -> holdService.release(releasedHold))
                .isInstanceOf(InvalidHoldStateException.class);

        UUID expiredHold = holdService.hold(resourceId, "ref-expire", Duration.ofMinutes(15));
        holdService.expire(expiredHold);
        assertThatThrownBy(() -> holdService.expire(expiredHold))
                .isInstanceOf(InvalidHoldStateException.class);

        UUID confirmedHold = holdService.hold(resourceId, "ref-confirm", Duration.ofMinutes(15));
        holdService.confirm(confirmedHold);
        assertThatThrownBy(() -> holdService.confirm(confirmedHold))
                .isInstanceOf(InvalidHoldStateException.class);

        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getEventType)
                .containsExactlyInAnyOrder(
                        "RESERVATION_HOLD_CREATED", "RESERVATION_RELEASED",
                        "RESERVATION_HOLD_CREATED", "RESERVATION_EXPIRED",
                        "RESERVATION_HOLD_CREATED", "RESERVATION_CONFIRMED");
    }

    @Test
    void shouldNotConfirmHoldIfSerializationFails() throws Exception {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));
        
        org.mockito.Mockito.doThrow(new com.fasterxml.jackson.core.JsonProcessingException("Simulated error") {})
            .when(objectMapper).writeValueAsString(org.mockito.Mockito.any());
            
        assertThatThrownBy(() -> holdService.confirm(holdId))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Failed to serialize RESERVATION_CONFIRMED event payload");
            
        Hold hold = holdRepository.findById(holdId).orElseThrow();
        assertThat(hold.getStatus()).isEqualTo(HoldStatus.ACTIVE); // Did NOT change to CONFIRMED
        assertThat(outboxRepository.findAll()).extracting(OutboxEvent::getEventType)
            .containsExactly("RESERVATION_HOLD_CREATED");
    }

    @Test
    void shouldCreateCanonicalEnvelopeForCreatedHold() throws Exception {
        UUID holdId = holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15));

        OutboxEvent event = outboxRepository.findAll().get(0);
        com.fasterxml.jackson.databind.JsonNode envelope = objectMapper.readTree(event.getPayload());

        assertThat(envelope.get("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo("RESERVATION_HOLD_CREATED");
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();
        assertThat(envelope.get("resourceId").asText()).isEqualTo(resourceId.toString());
        assertThat(envelope.get("holderRef").asText()).isEqualTo("ref-123");
        assertThat(envelope.get("payload").get("holdId").asText()).isEqualTo(holdId.toString());
        assertThat(envelope.get("payload").get("expiresAt").asText()).isNotBlank();
    }

    @Test
    void shouldRejectUnavailableResourceAndPersistOnlyRejectionEvent() throws Exception {
        Resource resource = resourceRepository.findById(resourceId).orElseThrow();
        resource.setAvailableUnits(0);
        resourceRepository.save(resource);

        assertThatThrownBy(() -> holdService.hold(resourceId, "ref-123", Duration.ofMinutes(15)))
                .isInstanceOf(com.ratchet.reservation.exception.InsufficientAvailabilityException.class);

        assertThat(holdRepository.findAll()).isEmpty();
        assertThat(resourceRepository.findById(resourceId).orElseThrow().getAvailableUnits()).isZero();
        OutboxEvent event = outboxRepository.findAll().stream().findFirst().orElseThrow();
        com.fasterxml.jackson.databind.JsonNode envelope = objectMapper.readTree(event.getPayload());
        assertThat(event.getEventType()).isEqualTo("RESERVATION_REJECTED");
        assertThat(envelope.get("eventType").asText()).isEqualTo("RESERVATION_REJECTED");
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("resourceId").asText()).isEqualTo(resourceId.toString());
        assertThat(envelope.get("holderRef").asText()).isEqualTo("ref-123");
        assertThat(envelope.get("payload").get("reason").asText()).isEqualTo("INSUFFICIENT_AVAILABILITY");
        assertThat(envelope.get("payload").get("requestedUnits").asInt()).isEqualTo(1);
        assertThat(envelope.get("payload").get("availableUnits").asInt()).isZero();
        assertThat(envelope.get("payload").has("holdId")).isFalse();
    }

    private com.fasterxml.jackson.databind.JsonNode assertCanonicalEvent(OutboxEvent event, String eventType,
                                                                           UUID holdId, String holderRef) throws Exception {
        com.fasterxml.jackson.databind.JsonNode envelope = objectMapper.readTree(event.getPayload());
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.getId().toString());
        assertThat(envelope.get("eventType").asText()).isEqualTo(eventType);
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(envelope.get("occurredAt").asText())).isNotNull();
        assertThat(envelope.get("resourceId").asText()).isEqualTo(resourceId.toString());
        assertThat(envelope.get("holderRef").asText()).isEqualTo(holderRef);
        assertThat(envelope.get("payload").get("holdId").asText()).isEqualTo(holdId.toString());
        return envelope;
    }
}
