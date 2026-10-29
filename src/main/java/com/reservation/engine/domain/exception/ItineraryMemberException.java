package com.reservation.engine.domain.exception;

/** A reservation that belongs to an itinerary can only be confirmed / cancelled through its itinerary. */
public class ItineraryMemberException extends DomainException {
    public ItineraryMemberException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "MANAGED_BY_ITINERARY";
    }
}
