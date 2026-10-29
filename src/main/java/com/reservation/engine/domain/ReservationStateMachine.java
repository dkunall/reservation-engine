package com.reservation.engine.domain;

import com.reservation.engine.domain.exception.InvalidStateTransitionException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.reservation.engine.domain.ReservationStatus.*;

/**
 * Explicit, table-driven reservation lifecycle.
 * <pre>
 * HELD -> PAYMENT_PENDING -> CONFIRMED -> CANCELLED
 * HELD -> CONFIRMED          (zero-amount reservations, no payment needed)
 * HELD -> EXPIRED | CANCELLED
 * PAYMENT_PENDING -> HELD    (payment declined while hold still valid)
 * PAYMENT_PENDING -> EXPIRED (payment declined after hold deadline)
 * </pre>
 */
public final class ReservationStateMachine {

    private static final Map<ReservationStatus, Set<ReservationStatus>> TRANSITIONS;

    static {
        Map<ReservationStatus, Set<ReservationStatus>> m = new EnumMap<>(ReservationStatus.class);
        m.put(HELD, EnumSet.of(PAYMENT_PENDING, CONFIRMED, EXPIRED, CANCELLED));
        m.put(PAYMENT_PENDING, EnumSet.of(CONFIRMED, HELD, EXPIRED));
        m.put(CONFIRMED, EnumSet.of(CANCELLED));
        m.put(CANCELLED, EnumSet.noneOf(ReservationStatus.class));
        m.put(EXPIRED, EnumSet.noneOf(ReservationStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(m);
    }

    private ReservationStateMachine() {
    }

    public static boolean canTransition(ReservationStatus from, ReservationStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertTransition(ReservationStatus from, ReservationStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(from, to);
        }
    }

    public static Set<ReservationStatus> allowedFrom(ReservationStatus from) {
        return Collections.unmodifiableSet(TRANSITIONS.getOrDefault(from, Set.of()));
    }
}
