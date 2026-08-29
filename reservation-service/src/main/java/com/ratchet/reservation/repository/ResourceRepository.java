package com.ratchet.reservation.repository;

import com.ratchet.reservation.domain.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ResourceRepository extends JpaRepository<Resource, UUID> {
}
