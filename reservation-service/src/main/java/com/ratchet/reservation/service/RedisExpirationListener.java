package com.ratchet.reservation.service;

import com.ratchet.reservation.exception.HoldNotFoundException;
import com.ratchet.reservation.exception.InvalidHoldStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RedisExpirationListener extends KeyExpirationEventMessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisExpirationListener.class);
    private final HoldService holdService;

    public RedisExpirationListener(RedisMessageListenerContainer listenerContainer, HoldService holdService) {
        super(listenerContainer);
        this.holdService = holdService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = message.toString();
        
        if (expiredKey.startsWith("hold:")) {
            String holdIdStr = expiredKey.substring("hold:".length());
            try {
                UUID holdId = UUID.fromString(holdIdStr);
                log.info("Received expiration event for hold: {}", holdId);
                holdService.expire(holdId);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid hold ID format in expired key: {}", expiredKey);
            } catch (InvalidHoldStateException | HoldNotFoundException e) {
                log.debug("Hold {} cannot be expired (it was likely confirmed, manually released, or not found): {}", holdIdStr, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error expiring hold {}", holdIdStr, e);
            }
        }
    }
}
