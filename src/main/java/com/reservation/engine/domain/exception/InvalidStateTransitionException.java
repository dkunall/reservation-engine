package com.reservation.engine.domain.exception;

import com.reservation.engine.domain.ReservationStatus;

public class InvalidStateTransitionException extends DomainException {
    private final ReservationStatus from;
    private final ReservationStatus to;

    public InvalidStateTransitionException(ReservationStatus from, ReservationStatus to) {
        super("Reservation cannot move from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    /** For lifecycles other than the reservation one (e.g. waitlist entries). */
    public InvalidStateTransitionException(String message) {
        super(message);
        this.from = null;
        this.to = null;
    }

    public ReservationStatus from() {
        return from;
    }

    public ReservationStatus to() {
        return to;
    }

    @Override
    public String code() {
        return "INVALID_STATE_TRANSITION";
    }
}
