package com.ratchet.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.UUID;

@Service
public class ReservationConfirmationClient {

    private static final Logger log = LoggerFactory.getLogger(ReservationConfirmationClient.class);
    private final RestTemplate restTemplate;
    private final String reservationServiceUrl;

    public ReservationConfirmationClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${reservation.service.url:http://localhost:8082}") String reservationServiceUrl) {
        
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
        this.reservationServiceUrl = reservationServiceUrl;
    }

    /**
     * Confirms a hold in the reservation-service.
     * @return ConfirmationResult indicating the outcome.
     */
    public ConfirmationResult confirmHold(UUID holdId) {
        if (holdId == null) {
            log.warn("Cannot confirm hold: holdId is null");
            return ConfirmationResult.TRANSIENT_FAILURE;
        }

        String url = String.format("%s/internal/holds/%s/confirm", reservationServiceUrl, holdId);
        
        try {
            ResponseEntity<Void> response = restTemplate.postForEntity(url, null, Void.class);
            return response.getStatusCode().is2xxSuccessful() ? ConfirmationResult.SUCCESS : ConfirmationResult.TRANSIENT_FAILURE;
        } catch (HttpClientErrorException e) {
            // 409 Conflict (e.g. Hold expired) or 404 Not Found
            log.warn("Failed to confirm hold {} due to business rule (status: {}). Manual intervention required.", holdId, e.getStatusCode());
            return ConfirmationResult.PERMANENT_FAILURE; 
        } catch (RestClientException e) {
            log.error("Network or server error while confirming hold {}", holdId, e);
            return ConfirmationResult.TRANSIENT_FAILURE;
        }
    }
}
