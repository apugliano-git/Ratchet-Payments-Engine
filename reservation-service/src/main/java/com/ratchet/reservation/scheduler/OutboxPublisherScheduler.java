package com.ratchet.reservation.scheduler;

import com.ratchet.reservation.domain.OutboxEvent;
import com.ratchet.reservation.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);
    private static final String TOPIC_HOLD_CONFIRMED = "reservation.hold.confirmed";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisherScheduler(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedRate = 5000)
    public void publishOutboxEvents() {
        List<OutboxEvent> pendingEvents = outboxRepository.findByProcessedAtIsNull();
        
        for (OutboxEvent event : pendingEvents) {
            try {
                if ("HoldConfirmed".equals(event.getEventType())) {
                    kafkaTemplate.send(TOPIC_HOLD_CONFIRMED, event.getAggregateId().toString(), event.getPayload())
                            .get(5, TimeUnit.SECONDS);
                    
                    event.setProcessedAt(Instant.now());
                    outboxRepository.save(event);
                }
            } catch (Exception e) {
                log.warn("Failed to publish outbox event id: {}, eventType: {}", event.getId(), event.getEventType(), e);
            }
        }
    }
}
