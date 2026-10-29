package com.reservation.engine.domain.exception;

public class PaymentDeclinedException extends DomainException {
    public PaymentDeclinedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "PAYMENT_DECLINED";
    }
}
