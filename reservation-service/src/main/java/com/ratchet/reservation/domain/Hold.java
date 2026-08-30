package com.ratchet.reservation.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

@Entity
public class Hold {
    @Id
    private UUID id;
    
    private UUID resourceId;
    
    private String holderRef;
    
    @Enumerated(EnumType.STRING)
    private HoldStatus status;
    
    private Instant createdAt;
    
    private Instant expiresAt;

    @jakarta.persistence.Version
    private Long version;

    public Hold() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public void setResourceId(UUID resourceId) {
        this.resourceId = resourceId;
    }

    public String getHolderRef() {
        return holderRef;
    }

    public void setHolderRef(String holderRef) {
        this.holderRef = holderRef;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public void setStatus(HoldStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
