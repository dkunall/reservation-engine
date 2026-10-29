package com.reservation.engine.extensions.vehicle;

import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.policy.CancellationDecision;
import com.reservation.engine.domain.policy.CancellationPolicy;

import java.time.Duration;
import java.time.Instant;

/** Free cancellation until 24h before pick-up, 50% refund afterwards, no cancellation once the rental started. */
public class VehicleCancellationPolicy implements CancellationPolicy {
    static final Duration FREE_UNTIL = Duration.ofHours(24);

    @Override
    public CancellationDecision evaluate(Reservation r, Instant now) {
        if (r.getStatus() != ReservationStatus.CONFIRMED) {
            return CancellationDecision.allow(r.getTotal().times(0), "not charged");
        }
        if (!now.isBefore(r.getStartsAt())) {
            return CancellationDecision.deny("rental already started");
        }
        if (now.isBefore(r.getStartsAt().minus(FREE_UNTIL))) {
            return CancellationDecision.allow(r.getTotal(), "free cancellation");
        }
        return CancellationDecision.allow(r.getTotal().percentage(50), "late cancellation: 50% refund");
    }
}
