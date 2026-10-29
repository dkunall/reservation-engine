package com.reservation.engine.domain.exception;

public class HoldExpiredException extends DomainException {
    public HoldExpiredException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "HOLD_EXPIRED";
    }
}
