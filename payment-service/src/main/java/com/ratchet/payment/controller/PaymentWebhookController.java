package com.ratchet.payment.controller;

import com.ratchet.payment.domain.PaymentEvent;
import com.ratchet.payment.domain.PaymentEventStatus;
import com.ratchet.payment.repository.PaymentEventRepository;
import com.ratchet.payment.service.ReservationConfirmationClient;
import com.ratchet.payment.service.WebhookSignatureVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/webhooks/mercadopago")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final WebhookSignatureVerifier signatureVerifier;
    private final PaymentEventRepository paymentEventRepository;
    private final ReservationConfirmationClient confirmationClient;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(
            WebhookSignatureVerifier signatureVerifier, 
            PaymentEventRepository paymentEventRepository,
            ReservationConfirmationClient confirmationClient,
            ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.paymentEventRepository = paymentEventRepository;
        this.confirmationClient = confirmationClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId) {

        if (!signatureVerifier.verifySignature(rawPayload, xSignature, xRequestId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String externalEventId = signatureVerifier.extractId(rawPayload);
        if (externalEventId == null) return ResponseEntity.ok().build();

        Optional<PaymentEvent> existingEvent = paymentEventRepository.findByExternalEventId(externalEventId);
        if (existingEvent.isPresent()) return ResponseEntity.ok().build();

        // Extraemos el holdId. 
        // TODO: En Mercado Pago, el webhook simple de notificación a veces no trae todo el payload, 
        // por lo que podría ser necesario hacer un GET /v1/payments/{id} para obtener el 'external_reference'.
        // Por ahora, asumimos que viene en el JSON para poder testear el circuito.
        UUID holdId = extractHoldId(rawPayload);

        PaymentEventStatus initialStatus = PaymentEventStatus.RECEIVED;
        if (holdId == null) {
            initialStatus = PaymentEventStatus.FAILED;
            log.error("CRITICAL: Webhook {} received without an extractable holdId. Event marked as FAILED and requires manual investigation. Raw payload: {}", externalEventId, rawPayload);
        }

        PaymentEvent newEvent = new PaymentEvent(
                externalEventId,
                holdId,
                initialStatus,
                rawPayload
        );
        
        try {
            newEvent = paymentEventRepository.save(newEvent);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            return ResponseEntity.ok().build();
        }

        // Intento sincrónico de confirmación
        if (holdId != null) {
            com.ratchet.payment.service.ConfirmationResult result = confirmationClient.confirmHold(holdId);
            try {
                if (result == com.ratchet.payment.service.ConfirmationResult.SUCCESS) {
                    newEvent.setStatus(PaymentEventStatus.PROCESSED);
                    paymentEventRepository.save(newEvent);
                } else if (result == com.ratchet.payment.service.ConfirmationResult.PERMANENT_FAILURE) {
                    newEvent.setStatus(PaymentEventStatus.FAILED);
                    paymentEventRepository.save(newEvent);
                    log.warn("Permanent failure confirming hold {}. Marked event as FAILED.", holdId);
                } else {
                    log.warn("Transient failure confirming hold {} synchronously. Will retry via scheduler.", holdId);
                }
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                log.info("Concurrent modification detected for event {}. Resolving state.", externalEventId);
                PaymentEvent latest = paymentEventRepository.findById(newEvent.getId()).orElse(null);
                if (latest != null && result == com.ratchet.payment.service.ConfirmationResult.SUCCESS) {
                    latest.setStatus(PaymentEventStatus.PROCESSED);
                    paymentEventRepository.save(latest);
                }
            }
        }

        return ResponseEntity.ok().build();
    }

    private UUID extractHoldId(String rawPayload) {
        try {
            JsonNode rootNode = objectMapper.readTree(rawPayload);
            if (rootNode.has("external_reference")) {
                String extRef = rootNode.get("external_reference").asText();
                return UUID.fromString(extRef);
            } else if (rootNode.has("data") && rootNode.get("data").has("external_reference")) {
                String extRef = rootNode.get("data").get("external_reference").asText();
                return UUID.fromString(extRef);
            }
        } catch (Exception e) {
            log.warn("Could not extract external_reference as UUID from payload", e);
        }
        return null;
    }
}
