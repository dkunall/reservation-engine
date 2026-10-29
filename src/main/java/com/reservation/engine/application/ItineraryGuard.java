package com.reservation.engine.application;

import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.exception.ItineraryMemberException;

/** Keeps the per-reservation endpoints from breaking the all-or-nothing contract of an itinerary. */
final class ItineraryGuard {
    private ItineraryGuard() {
    }

    static void assertStandalone(Reservation r) {
        if (r.getItineraryId() != null) {
            throw new ItineraryMemberException("Reservation " + r.getId() + " belongs to itinerary "
                    + r.getItineraryId() + "; confirm or cancel the itinerary instead");
        }
    }
}
