package com.reservation.engine.application;

public record ConfirmResult(ReservationView reservation, Outcome outcome) {
    public enum Outcome {
        /** Reservation is CONFIRMED. */
        CONFIRMED,
        /** Payment outcome unknown; reservation stays PAYMENT_PENDING until reconciliation. */
        PENDING,
        /** Payment declined; reservation returned to HELD (or EXPIRED if the hold lapsed). */
        DECLINED
    }
}
