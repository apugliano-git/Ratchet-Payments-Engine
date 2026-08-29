package com.ratchet.reservation.controller;

import java.util.UUID;

public record HoldRequest(UUID resourceId, int ttlMinutes) {
}
