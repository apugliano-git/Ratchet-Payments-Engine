package com.ratchet.payment.controller;

import com.ratchet.payment.domain.PaymentEvent;
import com.ratchet.payment.domain.PaymentEventStatus;
import com.ratchet.payment.repository.PaymentEventRepository;
import com.ratchet.payment.service.WebhookSignatureVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/webhooks/mercadopago")
public class PaymentWebhookController {

    private final WebhookSignatureVerifier signatureVerifier;
    private final PaymentEventRepository paymentEventRepository;

    public PaymentWebhookController(WebhookSignatureVerifier signatureVerifier, PaymentEventRepository paymentEventRepository) {
        this.signatureVerifier = signatureVerifier;
        this.paymentEventRepository = paymentEventRepository;
    }

    @PostMapping
    public ResponseEntity<Void> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "x-signature", required = false) String xSignature,
            @RequestHeader(value = "x-request-id", required = false) String xRequestId) {

        // 1. Verify signature
        if (!signatureVerifier.verifySignature(rawPayload, xSignature, xRequestId)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // 2. Extract Event ID
        String externalEventId = signatureVerifier.extractId(rawPayload);
        if (externalEventId == null) {
            // Bad request, but returning 200 prevents MP from retrying a malformed payload
            return ResponseEntity.ok().build();
        }

        // 3. Check for idempotency
        Optional<PaymentEvent> existingEvent = paymentEventRepository.findByExternalEventId(externalEventId);
        if (existingEvent.isPresent()) {
            return ResponseEntity.ok().build();
        }

        // 4. Create new PaymentEvent
        PaymentEvent newEvent = new PaymentEvent(
                externalEventId,
                null, // holdId is populated later
                PaymentEventStatus.RECEIVED,
                rawPayload
        );
        
        // This relies on the database UNIQUE constraint for externalEventId to handle race conditions
        try {
            paymentEventRepository.save(newEvent);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Another thread inserted it at the exact same time
            // We ignore it and return 200 OK
        }

        return ResponseEntity.ok().build();
    }
}
