package com.ratchet.notification.repository;

import com.ratchet.notification.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    Optional<NotificationLog> findByEventId(String eventId);
}
