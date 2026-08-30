package com.ratchet.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_logs")
public class NotificationLog {

    @Id
    private UUID id;

    @Column(unique = true, nullable = false)
    private String eventId;

    @Column(nullable = false)
    private UUID holdId;

    @Column(nullable = false)
    private String holderRef;

    @Column(nullable = false)
    private Instant notifiedAt;

    public NotificationLog() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public void setHoldId(UUID holdId) {
        this.holdId = holdId;
    }

    public String getHolderRef() {
        return holderRef;
    }

    public void setHolderRef(String holderRef) {
        this.holderRef = holderRef;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public void setNotifiedAt(Instant notifiedAt) {
        this.notifiedAt = notifiedAt;
    }
}
