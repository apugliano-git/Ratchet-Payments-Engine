package com.ratchet.reservation.controller;

import com.ratchet.reservation.domain.Hold;
import com.ratchet.reservation.domain.HoldStatus;
import com.ratchet.reservation.repository.HoldRepository;
import com.ratchet.reservation.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/holds")
public class HoldController {

    private final HoldService holdService;
    private final HoldRepository holdRepository;

    public HoldController(HoldService holdService, HoldRepository holdRepository) {
        this.holdService = holdService;
        this.holdRepository = holdRepository;
    }

    @PostMapping
    public ResponseEntity<?> createHold(@RequestBody HoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        String holderRef = jwt.getSubject();
        UUID holdId = holdService.hold(request.resourceId(), holderRef, Duration.ofMinutes(request.ttlMinutes()));
        return ResponseEntity.ok(Map.of("holdId", holdId));
    }

    @PostMapping("/{holdId}/release")
    public ResponseEntity<?> releaseHold(@PathVariable("holdId") UUID holdId, @AuthenticationPrincipal Jwt jwt) {
        String holderRef = jwt.getSubject();
        
        Hold hold = holdRepository.findById(holdId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hold not found"));
                
        if (!holderRef.equals(hold.getHolderRef())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to release this hold");
        }
        
        holdService.release(holdId);
        return ResponseEntity.ok().build();
    }
}
