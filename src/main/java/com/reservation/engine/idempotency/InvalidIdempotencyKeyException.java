package com.reservation.engine.idempotency;

import com.reservation.engine.domain.exception.DomainException;

public class InvalidIdempotencyKeyException extends DomainException {
    public InvalidIdempotencyKeyException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "INVALID_IDEMPOTENCY_KEY";
    }
}
