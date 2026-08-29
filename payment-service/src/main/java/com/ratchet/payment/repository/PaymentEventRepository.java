package com.ratchet.payment.repository;

import com.ratchet.payment.domain.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {
    Optional<PaymentEvent> findByExternalEventId(String externalEventId);
}
