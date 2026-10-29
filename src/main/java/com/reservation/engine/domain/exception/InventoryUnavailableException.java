package com.reservation.engine.domain.exception;

public class InventoryUnavailableException extends DomainException {
    public InventoryUnavailableException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "INVENTORY_UNAVAILABLE";
    }
}
