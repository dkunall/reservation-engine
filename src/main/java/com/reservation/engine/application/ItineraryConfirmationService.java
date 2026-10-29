package com.reservation.engine.application;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.exception.HoldExpiredException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.exception.PaymentDeclinedException;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.payment.PaymentPort;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ItineraryRepository;
import com.reservation.engine.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Confirms an itinerary with ONE payment for the total, using the same 3-step pattern as single reservations:
 * tx#1 (lock itinerary, then items) moves everything to PAYMENT_PENDING; the provider is called with no
 * transaction open; tx#2 ({@link ItineraryPaymentFinalizer}) applies the outcome to the whole unit.
 */
@Service
public class ItineraryConfirmationService {
    private static final Logger log = LoggerFactory.getLogger(ItineraryConfirmationService.class);

    private enum StartKind { ALREADY_CONFIRMED, CONFIRMED_WITHOUT_PAYMENT, EXPIRED, PAYMENT_REQUIRED }

    private record Start(StartKind kind, ItineraryView view, UUID paymentTxId, Money amount) {
    }

    private final ItineraryRepository itineraries;
    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final PaymentPort paymentPort;
    private final ItineraryPaymentFinalizer finalizer;
    private final ItineraryLifecycle lifecycle;
    private final ItineraryEventRecorder recorder;
    private final ReservationMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ItineraryConfirmationService(ItineraryRepository itineraries, ReservationRepository reservations,
                                        PaymentTransactionRepository payments, PaymentPort paymentPort,
                                        ItineraryPaymentFinalizer finalizer, ItineraryLifecycle lifecycle,
                                        ItineraryEventRecorder recorder, ReservationMetrics metrics,
                                        TransactionTemplate tx, Clock clock) {
        this.itineraries = itineraries;
        this.reservations = reservations;
        this.payments = payments;
        this.paymentPort = paymentPort;
        this.finalizer = finalizer;
        this.lifecycle = lifecycle;
        this.recorder = recorder;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    public ItineraryConfirmResult confirm(CurrentUser user, UUID itineraryId, String paymentMethodToken) {
        return metrics.time("itinerary.confirm.duration", () -> doConfirm(user, itineraryId, paymentMethodToken));
    }

    private ItineraryConfirmResult doConfirm(CurrentUser user, UUID itineraryId, String paymentMethodToken) {
        Start start = tx.execute(status -> begin(user, itineraryId));
        switch (start.kind()) {
            case ALREADY_CONFIRMED, CONFIRMED_WITHOUT_PAYMENT -> {
                return new ItineraryConfirmResult(start.view(), ConfirmResult.Outcome.CONFIRMED);
            }
            case EXPIRED -> {
                metrics.itineraryConfirm("expired");
                throw new HoldExpiredException("Hold for itinerary " + itineraryId + " has expired");
            }
            default -> {
                // payment required
            }
        }
        PaymentPort.PaymentResult result;
        try {
            result = paymentPort.charge(new PaymentPort.ChargeRequest(start.paymentTxId().toString(), itineraryId,
                    start.amount(), paymentMethodToken));
        } catch (RuntimeException e) {
            log.warn("payment call failed; outcome unknown itinerary={} paymentTx={}", itineraryId, start.paymentTxId(), e);
            result = PaymentPort.PaymentResult.unknown("provider error: " + e.getMessage());
        }
        ItineraryConfirmResult outcome = finalizer.finalizeCharge(itineraryId, start.paymentTxId(), result, user.userId());
        if (outcome.outcome() == ConfirmResult.Outcome.DECLINED) {
            throw new PaymentDeclinedException("Payment declined for itinerary " + itineraryId
                    + "; itinerary is now " + outcome.itinerary().status());
        }
        return outcome;
    }

    private Start begin(CurrentUser user, UUID itineraryId) {
        Itinerary it = itineraries.findByIdForUpdate(itineraryId)
                .orElseThrow(() -> new NotFoundException("Itinerary " + itineraryId + " not found"));
        AccessPolicy.assertCanAccess(user, it);
        List<Reservation> items = reservations.findByItineraryIdForUpdate(itineraryId);
        Instant now = clock.instant();

        if (it.getStatus() == ReservationStatus.CONFIRMED) {
            return new Start(StartKind.ALREADY_CONFIRMED, ItineraryView.from(it, items), null, null);
        }
        if (it.getStatus() == ReservationStatus.HELD && it.isHoldExpired(now)) {
            lifecycle.expire(it, items, "system", "hold expired before confirmation", now);
            recorder.record(it, items);
            return new Start(StartKind.EXPIRED, ItineraryView.from(it, items), null, null);
        }
        if (it.getStatus() == ReservationStatus.HELD && it.getTotal().isZero()) {
            lifecycle.confirm(it, items, user.userId(), now);
            recorder.record(it, items);
            metrics.itineraryConfirm("confirmed");
            return new Start(StartKind.CONFIRMED_WITHOUT_PAYMENT, ItineraryView.from(it, items), null, null);
        }
        lifecycle.startPayment(it, items, user.userId(), now); // InvalidStateTransition for other states
        PaymentTransaction charge = payments.save(PaymentTransaction.forItinerary(it.getId(),
                PaymentTransaction.Type.CHARGE, it.getTotal(), now));
        recorder.record(it, items);
        return new Start(StartKind.PAYMENT_REQUIRED, ItineraryView.from(it, items), charge.getId(), it.getTotal());
    }
}
