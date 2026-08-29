package com.ratchet.reservation.exception;

public class InvalidHoldStateException extends RuntimeException {
    public InvalidHoldStateException(String message) {
        super(message);
    }
}
