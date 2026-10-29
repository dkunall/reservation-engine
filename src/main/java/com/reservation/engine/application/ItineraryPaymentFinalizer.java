package com.reservation.engine.application;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.payment.PaymentPort;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ItineraryRepository;
import com.reservation.engine.persistence.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Applies the outcome of the single itinerary charge to the itinerary and all items in a NEW short transaction.
 * Guarded exactly like {@link PaymentFinalizer} so the request thread and the reconciliation job can race safely.
 */
@Component
public class ItineraryPaymentFinalizer {
    private final ItineraryRepository itineraries;
    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final ItineraryLifecycle lifecycle;
    private final ItineraryEventRecorder recorder;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public ItineraryPaymentFinalizer(ItineraryRepository itineraries, ReservationRepository reservations,
                                     PaymentTransactionRepository payments, ItineraryLifecycle lifecycle,
                                     ItineraryEventRecorder recorder, ReservationMetrics metrics, Clock clock) {
        this.itineraries = itineraries;
        this.reservations = reservations;
        this.payments = payments;
        this.lifecycle = lifecycle;
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ItineraryConfirmResult finalizeCharge(UUID itineraryId, UUID paymentTxId, PaymentPort.PaymentResult result,
                                                 String actor) {
        Itinerary it = itineraries.findByIdForUpdate(itineraryId)
                .orElseThrow(() -> new NotFoundException("Itinerary " + itineraryId + " not found"));
        List<Reservation> items = reservations.findByItineraryIdForUpdate(itineraryId);
        PaymentTransaction p = payments.findById(paymentTxId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentTxId + " not found"));
        Instant now = clock.instant();
        if (it.getStatus() != ReservationStatus.PAYMENT_PENDING || !p.getStatus().isUnresolved()) {
            return new ItineraryConfirmResult(ItineraryView.from(it, items), outcomeOf(it.getStatus()));
        }
        ConfirmResult.Outcome outcome;
        switch (result.outcome()) {
            case SUCCEEDED -> {
                p.resolve(PaymentTransaction.Status.SUCCEEDED, result.providerReference(), null, now);
                lifecycle.confirm(it, items, actor, now);
                outcome = ConfirmResult.Outcome.CONFIRMED;
            }
            case DECLINED -> {
                p.resolve(PaymentTransaction.Status.DECLINED, result.providerReference(), result.message(), now);
                if (it.isHoldExpired(now)) {
                    lifecycle.expire(it, items, actor, "payment declined after hold expiry", now);
                } else {
                    lifecycle.revertToHeld(it, items, actor, "payment declined", now);
                }
                outcome = ConfirmResult.Outcome.DECLINED;
            }
            default -> {
                p.resolve(PaymentTransaction.Status.UNKNOWN, null, result.message(), now);
                outcome = ConfirmResult.Outcome.PENDING;
            }
        }
        recorder.record(it, items);
        metrics.itineraryConfirm(outcome.name().toLowerCase());
        return new ItineraryConfirmResult(ItineraryView.from(it, items), outcome);
    }

    private static ConfirmResult.Outcome outcomeOf(ReservationStatus s) {
        return switch (s) {
            case CONFIRMED, CANCELLED -> ConfirmResult.Outcome.CONFIRMED;
            case PAYMENT_PENDING -> ConfirmResult.Outcome.PENDING;
            default -> ConfirmResult.Outcome.DECLINED;
        };
    }
}
