package com.ratchet.reservation.exception;

public class HoldNotFoundException extends RuntimeException {
    public HoldNotFoundException(String message) {
        super(message);
    }
}
