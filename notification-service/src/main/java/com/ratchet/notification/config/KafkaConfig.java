package com.ratchet.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public DefaultErrorHandler errorHandler() {
        FixedBackOff backOff = new FixedBackOff(1000L, 2);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(backOff);
        
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.warn("Delivery attempt {} failed for record {}", deliveryAttempt, record.value());
            if (deliveryAttempt >= 3) { // 1 initial + 2 retries
                log.error("CRITICAL: Message exhausted retries and will be skipped. Payload: {}", record.value(), ex);
            }
        });
        
        return errorHandler;
    }
}
