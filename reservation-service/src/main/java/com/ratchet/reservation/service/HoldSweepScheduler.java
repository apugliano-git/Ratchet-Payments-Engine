package com.ratchet.reservation.service;

import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.exception.HoldNotFoundException;
import com.ratchet.reservation.exception.InvalidHoldStateException;
import com.ratchet.reservation.repository.HoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class HoldSweepScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldSweepScheduler.class);
    private final HoldRepository holdRepository;
    private final HoldService holdService;

    public HoldSweepScheduler(HoldRepository holdRepository, HoldService holdService) {
        this.holdRepository = holdRepository;
        this.holdService = holdService;
    }

    @Scheduled(fixedRate = 60000)
    public void sweepExpiredHolds() {
        List<Hold> expiredHolds = holdRepository.findByStatusAndExpiresAtBefore(HoldStatus.ACTIVE, Instant.now());
        if (!expiredHolds.isEmpty()) {
            log.warn("Found {} expired holds not cleared by Redis. Running sweep...", expiredHolds.size());
            for (Hold hold : expiredHolds) {
                try {
                    holdService.expire(hold.getId());
                } catch (InvalidHoldStateException | HoldNotFoundException e) {
                    log.debug("Hold {} already processed during sweep: {}", hold.getId(), e.getMessage());
                } catch (Exception e) {
                    log.error("Failed to expire hold {} during sweep", hold.getId(), e);
                }
            }
        }
    }
}
