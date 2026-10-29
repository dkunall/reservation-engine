package com.reservation.engine.domain.policy;

import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Holds: always cancellable, nothing to refund.
 * Confirmed: full refund until {@code cutoff} before the reservation start; denied afterwards.
 * Reservations without a start time are always fully refundable.
 */
public class DefaultCancellationPolicy implements CancellationPolicy {
    private final Duration cutoffBeforeStart;

    public DefaultCancellationPolicy(Duration cutoffBeforeStart) {
        this.cutoffBeforeStart = cutoffBeforeStart;
    }

    @Override
    public CancellationDecision evaluate(Reservation r, Instant now) {
        Money zero = r.getTotal().times(0);
        if (r.getStatus() != ReservationStatus.CONFIRMED) {
            // state machine rejects invalid states; nothing was charged for holds
            return CancellationDecision.allow(zero, "not charged");
        }
        if (r.getStartsAt() != null && now.isAfter(r.getStartsAt().minus(cutoffBeforeStart))) {
            return CancellationDecision.deny("cancellation window closed " + cutoffBeforeStart + " before start");
        }
        return CancellationDecision.allow(r.getTotal(), "full refund");
    }
}
