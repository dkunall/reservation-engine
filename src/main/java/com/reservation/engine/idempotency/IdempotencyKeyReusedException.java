package com.reservation.engine.idempotency;

import com.reservation.engine.domain.exception.DomainException;

public class IdempotencyKeyReusedException extends DomainException {
    public IdempotencyKeyReusedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "IDEMPOTENCY_KEY_REUSED";
    }
}
