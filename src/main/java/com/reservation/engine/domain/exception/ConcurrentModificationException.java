package com.reservation.engine.domain.exception;

public class ConcurrentModificationException extends DomainException {
    public ConcurrentModificationException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "CONCURRENT_MODIFICATION";
    }
}
