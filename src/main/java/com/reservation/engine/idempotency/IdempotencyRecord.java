package com.reservation.engine.idempotency;

import java.time.Instant;

public record IdempotencyRecord(long id, String userId, String key, String operation, String requestHash,
                                Status status, Integer responseStatus, String responseBody, Instant updatedAt) {
    public enum Status { IN_PROGRESS, COMPLETED }
}
