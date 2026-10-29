package com.reservation.engine.domain.exception;

public class NotFoundException extends DomainException {
    public NotFoundException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "NOT_FOUND";
    }
}
