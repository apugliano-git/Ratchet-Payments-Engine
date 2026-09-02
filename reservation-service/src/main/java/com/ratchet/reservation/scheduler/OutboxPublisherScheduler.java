package com.ratchet.reservation.scheduler;

import com.ratchet.reservation.domain.OutboxEvent;
import com.ratchet.reservation.repository.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);
    private static final String TOPIC_RESERVATION_EVENTS = "reservation.events.v1";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisherScheduler(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                                    ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedRate = 5000)
    public void publishOutboxEvents() {
        // ponytail: one publisher with createdAt/id order; add row claiming only when multi-publisher is enabled.
        List<OutboxEvent> pendingEvents = outboxRepository.findByProcessedAtIsNullOrderByCreatedAtAscIdAsc();
        
        for (OutboxEvent event : pendingEvents) {
            try {
                UUID resourceId = event.getResourceId() != null
                        ? event.getResourceId()
                        : UUID.fromString(objectMapper.readTree(event.getPayload()).get("resourceId").asText());
                kafkaTemplate.send(TOPIC_RESERVATION_EVENTS, resourceId.toString(), canonicalPayload(event))
                        .get(5, TimeUnit.SECONDS);

                event.setProcessedAt(Instant.now());
                outboxRepository.save(event);
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id: {}, eventType: {}", event.getId(), event.getEventType(), e);
                break;
            }
        }
    }

    private String canonicalPayload(OutboxEvent event) throws Exception {
        if (!"HoldConfirmed".equals(event.getEventType())) {
            return event.getPayload();
        }

        JsonNode legacy = objectMapper.readTree(event.getPayload());
        java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", legacy.get("eventId"));
        envelope.put("eventType", "RESERVATION_CONFIRMED");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", event.getCreatedAt().toString());
        envelope.put("resourceId", legacy.get("resourceId"));
        envelope.put("holderRef", legacy.get("holderRef"));
        envelope.put("payload", java.util.Map.of("holdId", legacy.get("holdId")));
        return objectMapper.writeValueAsString(envelope);
    }
}
