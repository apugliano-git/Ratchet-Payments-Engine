package com.ratchet.notification.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ratchet.notification.domain.NotificationLog;
import com.ratchet.notification.repository.NotificationLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class HoldConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(HoldConfirmedListener.class);

    private final ObjectMapper objectMapper;
    private final NotificationLogRepository repository;

    public HoldConfirmedListener(ObjectMapper objectMapper, NotificationLogRepository repository) {
        this.objectMapper = objectMapper;
        this.repository = repository;
    }

    @KafkaListener(topics = "reservation.hold.confirmed", groupId = "${spring.kafka.consumer.group-id}")
    public void onHoldConfirmed(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("Malformed message received: invalid JSON. Payload: {}", payload, e);
            return;
        }
        
        if (!root.has("eventId") || root.get("eventId").isNull()) {
            log.error("Malformed message received: missing eventId. Payload: {}", payload);
            return;
        }
        
        if (!root.has("holdId") || root.get("holdId").isNull() || 
            !root.has("holderRef") || root.get("holderRef").isNull()) {
            log.error("Malformed message received: missing holdId or holderRef. Payload: {}", payload);
            return;
        }
        
        String eventId = root.get("eventId").asText();
        UUID holdId;
        try {
            holdId = UUID.fromString(root.get("holdId").asText());
        } catch (IllegalArgumentException e) {
            log.error("Malformed message received: invalid holdId UUID. Payload: {}", payload, e);
            return;
        }
        
        String holderRef = root.get("holderRef").asText();

        try {
            Optional<NotificationLog> existingLog = repository.findByEventId(eventId);
            if (existingLog.isPresent()) {
                log.info("Duplicate notification ignored for eventId: {}", eventId);
                return;
            }

            log.info("Notificación simulada: se confirmó la reserva {} para el usuario {}", holdId, holderRef);

            NotificationLog notificationLog = new NotificationLog();
            notificationLog.setId(UUID.randomUUID());
            notificationLog.setEventId(eventId);
            notificationLog.setHoldId(holdId);
            notificationLog.setHolderRef(holderRef);
            notificationLog.setNotifiedAt(Instant.now());

            repository.save(notificationLog);
            
        } catch (Exception e) {
            log.error("Database or infrastructure error processing message, will be retried. Payload: {}", payload, e);
            throw new RuntimeException("Error processing HoldConfirmed message", e);
        }
    }
}
