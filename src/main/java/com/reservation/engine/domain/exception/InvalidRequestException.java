package com.reservation.engine.domain.exception;

public class InvalidRequestException extends DomainException {
    public InvalidRequestException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "INVALID_REQUEST";
    }
}
