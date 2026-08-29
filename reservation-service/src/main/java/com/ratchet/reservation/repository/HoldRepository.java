package com.ratchet.reservation.repository;

import com.ratchet.reservation.domain.Hold;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface HoldRepository extends JpaRepository<Hold, UUID> {
}
