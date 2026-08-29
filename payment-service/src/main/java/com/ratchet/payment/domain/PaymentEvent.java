package com.ratchet.payment.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String externalEventId;

    private UUID holdId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentEventStatus status;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String rawPayload;

    @Column(nullable = false)
    private Instant receivedAt;

    @Version
    private Long version;

    protected PaymentEvent() {}

    public PaymentEvent(String externalEventId, UUID holdId, PaymentEventStatus status, String rawPayload) {
        this.externalEventId = externalEventId;
        this.holdId = holdId;
        this.status = status;
        this.rawPayload = rawPayload;
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public PaymentEventStatus getStatus() {
        return status;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setStatus(PaymentEventStatus status) {
        this.status = status;
    }
}
