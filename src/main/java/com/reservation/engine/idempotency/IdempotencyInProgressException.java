package com.reservation.engine.idempotency;

import com.reservation.engine.domain.exception.DomainException;

public class IdempotencyInProgressException extends DomainException {
    public IdempotencyInProgressException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "IDEMPOTENCY_REQUEST_IN_PROGRESS";
    }
}
