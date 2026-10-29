package com.reservation.engine.domain.policy;

import com.reservation.engine.domain.Reservation;

import java.time.Instant;

/** Decides whether a reservation may be cancelled now and how much is refunded. */
public interface CancellationPolicy {
    CancellationDecision evaluate(Reservation reservation, Instant now);
}
