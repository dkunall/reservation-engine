package com.reservation.engine.domain.exception;

/** Base for business errors. Mapped to HTTP problems in the api layer, never here. */
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }

    /** Stable machine-readable error code, e.g. "INVENTORY_UNAVAILABLE". */
    public abstract String code();
}
