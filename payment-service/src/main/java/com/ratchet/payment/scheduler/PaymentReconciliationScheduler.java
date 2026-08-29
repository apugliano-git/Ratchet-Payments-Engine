package com.ratchet.payment.scheduler;

import com.ratchet.payment.domain.PaymentEvent;
import com.ratchet.payment.domain.PaymentEventStatus;
import com.ratchet.payment.repository.PaymentEventRepository;
import com.ratchet.payment.service.ReservationConfirmationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PaymentReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationScheduler.class);

    private final PaymentEventRepository paymentEventRepository;
    private final ReservationConfirmationClient confirmationClient;

    public PaymentReconciliationScheduler(PaymentEventRepository paymentEventRepository,
                                          ReservationConfirmationClient confirmationClient) {
        this.paymentEventRepository = paymentEventRepository;
        this.confirmationClient = confirmationClient;
    }

    @Scheduled(fixedRate = 30000)
    public void reconcilePendingPayments() {
        // En una app real esto debería buscar con paginación
        List<PaymentEvent> pendingEvents = paymentEventRepository.findAll().stream()
                .filter(e -> e.getStatus() == PaymentEventStatus.RECEIVED)
                .toList();

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending payment events to reconcile", pendingEvents.size());

        for (PaymentEvent event : pendingEvents) {
            if (event.getHoldId() == null) {
                log.warn("Payment event {} has no holdId, cannot reconcile yet", event.getExternalEventId());
                continue;
            }

            com.ratchet.payment.service.ConfirmationResult result = confirmationClient.confirmHold(event.getHoldId());
            try {
                if (result == com.ratchet.payment.service.ConfirmationResult.SUCCESS) {
                    event.setStatus(PaymentEventStatus.PROCESSED);
                    paymentEventRepository.save(event);
                    log.info("Successfully reconciled and confirmed hold {} for payment event {}", event.getHoldId(), event.getExternalEventId());
                } else if (result == com.ratchet.payment.service.ConfirmationResult.PERMANENT_FAILURE) {
                    event.setStatus(PaymentEventStatus.FAILED);
                    paymentEventRepository.save(event);
                    log.warn("Permanent failure reconciling hold {}, marking event {} as FAILED", event.getHoldId(), event.getExternalEventId());
                } else {
                    log.warn("Transient failure reconciling hold {}, leaving payment event {} as RECEIVED", event.getHoldId(), event.getExternalEventId());
                }
            } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
                log.info("Concurrent modification detected for event {}. Resolving state.", event.getExternalEventId());
                PaymentEvent latest = paymentEventRepository.findById(event.getId()).orElse(null);
                if (latest != null && result == com.ratchet.payment.service.ConfirmationResult.SUCCESS) {
                    latest.setStatus(PaymentEventStatus.PROCESSED);
                    paymentEventRepository.save(latest);
                }
            }
        }
    }
}
