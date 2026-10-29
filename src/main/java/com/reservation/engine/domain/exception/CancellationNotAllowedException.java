package com.reservation.engine.domain.exception;

public class CancellationNotAllowedException extends DomainException {
    public CancellationNotAllowedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "CANCELLATION_NOT_ALLOWED";
    }
}
