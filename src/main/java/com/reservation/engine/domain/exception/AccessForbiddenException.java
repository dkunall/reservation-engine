package com.reservation.engine.domain.exception;

public class AccessForbiddenException extends DomainException {
    public AccessForbiddenException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "FORBIDDEN";
    }
}
