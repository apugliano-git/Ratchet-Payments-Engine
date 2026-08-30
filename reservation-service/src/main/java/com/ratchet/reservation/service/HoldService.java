package com.ratchet.reservation.service;

import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.domain.Resource;
import com.ratchet.reservation.exception.HoldNotFoundException;
import com.ratchet.reservation.exception.InsufficientAvailabilityException;
import com.ratchet.reservation.exception.InvalidHoldStateException;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.repository.ResourceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

import com.ratchet.reservation.domain.OutboxEvent;
import com.ratchet.reservation.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class HoldService {

    private final ResourceRepository resourceRepository;
    private final HoldRepository holdRepository;
    private final org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public HoldService(ResourceRepository resourceRepository, HoldRepository holdRepository, org.springframework.data.redis.core.StringRedisTemplate redisTemplate, OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.resourceRepository = resourceRepository;
        this.holdRepository = holdRepository;
        this.redisTemplate = redisTemplate;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @Retryable(retryFor = org.springframework.orm.ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
    public UUID hold(UUID resourceId, String holderRef, Duration ttl) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found"));

        if (resource.getAvailableUnits() <= 0) {
            throw new InsufficientAvailabilityException("No units available for resource: " + resourceId);
        }

        resource.setAvailableUnits(resource.getAvailableUnits() - 1);
        resourceRepository.save(resource);

        Hold hold = new Hold();
        hold.setId(UUID.randomUUID());
        hold.setResourceId(resourceId);
        hold.setHolderRef(holderRef);
        hold.setStatus(HoldStatus.ACTIVE);
        Instant now = Instant.now();
        hold.setCreatedAt(now);
        hold.setExpiresAt(now.plus(ttl));
        holdRepository.save(hold);

        try {
            redisTemplate.opsForValue().set("hold:" + hold.getId(), resourceId.toString(), ttl);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(HoldService.class).warn("Failed to write hold to Redis, relying on sweep scheduler", e);
        }

        return hold.getId();
    }

    @Transactional
    @Retryable(retryFor = org.springframework.orm.ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
    public void release(UUID holdId) {
        Hold hold = getHoldOrThrow(holdId);
        
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new InvalidHoldStateException("Cannot release hold in state: " + hold.getStatus());
        }
        
        hold.setStatus(HoldStatus.RELEASED);
        holdRepository.save(hold);
        
        incrementResourceUnits(hold.getResourceId());
    }

    @Transactional
    public void confirm(UUID holdId) {
        Hold hold = getHoldOrThrow(holdId);
        
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new InvalidHoldStateException("Cannot confirm hold in state: " + hold.getStatus());
        }
        
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(UUID.randomUUID());
        outboxEvent.setAggregateType("Hold");
        outboxEvent.setAggregateId(holdId);
        outboxEvent.setEventType("HoldConfirmed");
        
        java.util.Map<String, Object> payloadMap = new java.util.HashMap<>();
        payloadMap.put("eventId", outboxEvent.getId());
        payloadMap.put("holdId", hold.getId());
        payloadMap.put("resourceId", hold.getResourceId());
        payloadMap.put("holderRef", hold.getHolderRef());
        payloadMap.put("confirmedAt", Instant.now().toString());
        
        try {
            outboxEvent.setPayload(objectMapper.writeValueAsString(payloadMap));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize HoldConfirmed event payload", e);
        }
        
        outboxEvent.setCreatedAt(Instant.now());
        outboxEvent.setProcessedAt(null);
        
        hold.setStatus(HoldStatus.CONFIRMED);
        holdRepository.save(hold);
        
        outboxRepository.save(outboxEvent);
    }

    @Transactional
    @Retryable(retryFor = org.springframework.orm.ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 50))
    public void expire(UUID holdId) {
        Hold hold = getHoldOrThrow(holdId);
        
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new InvalidHoldStateException("Cannot expire hold in state: " + hold.getStatus());
        }
        
        hold.setStatus(HoldStatus.EXPIRED);
        holdRepository.save(hold);
        
        incrementResourceUnits(hold.getResourceId());
    }
    
    private Hold getHoldOrThrow(UUID holdId) {
        return holdRepository.findById(holdId)
                .orElseThrow(() -> new HoldNotFoundException("Hold not found: " + holdId));
    }
    
    private void incrementResourceUnits(UUID resourceId) {
        Resource resource = resourceRepository.findById(resourceId)
                .orElseThrow(() -> new IllegalArgumentException("Resource not found"));
        resource.setAvailableUnits(resource.getAvailableUnits() + 1);
        resourceRepository.save(resource);
    }
}
