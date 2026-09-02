package com.ratchet.reservation.repository;

import com.ratchet.reservation.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    List<OutboxEvent> findByProcessedAtIsNull();
    List<OutboxEvent> findByProcessedAtIsNullOrderByCreatedAtAscIdAsc();
}
