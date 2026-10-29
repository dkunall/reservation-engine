package com.reservation.engine.domain;

public enum ReservationStatus {
    HELD, PAYMENT_PENDING, CONFIRMED, CANCELLED, EXPIRED;

    public boolean isTerminal() {
        return this == CANCELLED || this == EXPIRED;
    }

    /** Statuses for which the reservation still owns inventory. */
    public boolean holdsInventory() {
        return this == HELD || this == PAYMENT_PENDING || this == CONFIRMED;
    }
}
