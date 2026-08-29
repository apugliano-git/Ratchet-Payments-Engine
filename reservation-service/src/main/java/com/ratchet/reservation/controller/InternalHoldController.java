package com.ratchet.reservation.controller;

import com.ratchet.reservation.exception.InvalidHoldStateException;
import com.ratchet.reservation.exception.HoldNotFoundException;
import com.ratchet.reservation.service.HoldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/internal/holds")
public class InternalHoldController {

    private final HoldService holdService;

    public InternalHoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    // SEGURIDAD - DEUDA TÉCNICA v1: este endpoint no tiene autenticación de servicio-a-servicio. 
    // Solo protegido por no estar expuesto fuera de la red interna de Docker. Antes de cualquier 
    // despliegue real, agregar autenticación mutua (mTLS) o un token de servicio compartido entre 
    // payment-service y reservation-service.
    @PostMapping("/{holdId}/confirm")
    public ResponseEntity<Void> confirmHold(@PathVariable UUID holdId) {
        try {
            holdService.confirm(holdId);
            return ResponseEntity.ok().build();
        } catch (InvalidHoldStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (HoldNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
