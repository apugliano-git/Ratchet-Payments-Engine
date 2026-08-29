package com.ratchet.reservation.controller;

import com.ratchet.reservation.exception.HoldNotFoundException;
import com.ratchet.reservation.exception.InsufficientAvailabilityException;
import com.ratchet.reservation.exception.InvalidHoldStateException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientAvailabilityException.class)
    public ResponseEntity<?> handleInsufficientAvailability(InsufficientAvailabilityException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidHoldStateException.class)
    public ResponseEntity<?> handleInvalidHoldState(InvalidHoldStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(HoldNotFoundException.class)
    public ResponseEntity<?> handleHoldNotFound(HoldNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<?> handleOptimisticLocking(org.springframework.orm.ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "El recurso está siendo modificado, intentá de nuevo"));
    }
}
