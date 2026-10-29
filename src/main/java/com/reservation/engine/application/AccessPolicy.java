package com.reservation.engine.application;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.exception.AccessForbiddenException;

/** Ownership rule: users only touch their own reservations; admins may access any. */
public final class AccessPolicy {
    private AccessPolicy() {
    }

    public static void assertCanAccess(CurrentUser user, Reservation reservation) {
        if (!user.admin() && !reservation.isOwnedBy(user.userId())) {
            throw new AccessForbiddenException("Reservation " + reservation.getId() + " belongs to another user");
        }
    }

    public static void assertCanAccess(CurrentUser user, Itinerary itinerary) {
        if (!user.admin() && !itinerary.isOwnedBy(user.userId())) {
            throw new AccessForbiddenException("Itinerary " + itinerary.getId() + " belongs to another user");
        }
    }
}
