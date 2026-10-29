package com.reservation.engine.application;

import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.outbox.OutboxWriter;
import com.reservation.engine.payment.PaymentPort;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a payment outcome in a NEW short transaction. Guarded by the reservation row lock and by
 * "still PAYMENT_PENDING and charge still unresolved" checks, so the request thread and the reconciliation
 * job can race safely: whichever runs first wins, the other becomes a no-op.
 */
@Component
public class PaymentFinalizer {

    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final InventoryAllocator inventory;
    private final ReservationEventRecorder recorder;
    private final OutboxWriter outbox;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public PaymentFinalizer(ReservationRepository reservations, PaymentTransactionRepository payments,
                            InventoryAllocator inventory, ReservationEventRecorder recorder, OutboxWriter outbox,
                            ReservationMetrics metrics, Clock clock) {
        this.reservations = reservations;
        this.payments = payments;
        this.inventory = inventory;
        this.recorder = recorder;
        this.outbox = outbox;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConfirmResult finalizeCharge(UUID reservationId, UUID paymentTxId, PaymentPort.PaymentResult result, String actor) {
        Reservation r = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation " + reservationId + " not found"));
        PaymentTransaction p = payments.findById(paymentTxId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentTxId + " not found"));
        Instant now = clock.instant();

        if (r.getStatus() != ReservationStatus.PAYMENT_PENDING || !p.getStatus().isUnresolved()) {
            return new ConfirmResult(ReservationView.from(r), outcomeOf(r.getStatus()));
        }
        ConfirmResult.Outcome outcome;
        switch (result.outcome()) {
            case SUCCEEDED -> {
                p.resolve(PaymentTransaction.Status.SUCCEEDED, result.providerReference(), null, now);
                r.confirm(actor, now);
                outcome = ConfirmResult.Outcome.CONFIRMED;
            }
            case DECLINED -> {
                p.resolve(PaymentTransaction.Status.DECLINED, result.providerReference(), result.message(), now);
                if (r.isHoldExpired(now)) {
                    r.expire(actor, "payment declined after hold expiry", now);
                    inventory.release(r, now);
                } else {
                    r.revertToHeld(actor, "payment declined", now);
                }
                outcome = ConfirmResult.Outcome.DECLINED;
            }
            default -> {
                p.resolve(PaymentTransaction.Status.UNKNOWN, null, result.message(), now);
                outcome = ConfirmResult.Outcome.PENDING;
            }
        }
        recorder.record(r);
        metrics.confirmOutcome(r.getResourceType(), outcome.name().toLowerCase());
        return new ConfirmResult(ReservationView.from(r), outcome);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeRefund(UUID paymentTxId, PaymentPort.PaymentResult result) {
        PaymentTransaction p = payments.findById(paymentTxId)
                .orElseThrow(() -> new NotFoundException("Payment " + paymentTxId + " not found"));
        if (!p.getStatus().isUnresolved()) {
            return;
        }
        Instant now = clock.instant();
        PaymentTransaction.Status status = switch (result.outcome()) {
            case SUCCEEDED -> PaymentTransaction.Status.SUCCEEDED;
            case DECLINED -> PaymentTransaction.Status.FAILED;
            case UNKNOWN -> PaymentTransaction.Status.UNKNOWN;
        };
        p.resolve(status, result.providerReference(), result.message(), now);
        if (status == PaymentTransaction.Status.SUCCEEDED) {
            Map<String, Object> payload = new LinkedHashMap<>();
            boolean itinerary = p.isItineraryPayment();
            payload.put(itinerary ? "itineraryId" : "reservationId", p.getOwnerId().toString());
            payload.put("paymentTransactionId", p.getId().toString());
            payload.put("amount", p.getAmount().amount().toPlainString());
            payload.put("currency", p.getAmount().currency());
            outbox.append(itinerary ? "Itinerary" : "Reservation", p.getOwnerId(),
                    itinerary ? "ItineraryRefunded" : "ReservationRefunded", payload);
        }
    }

    private static ConfirmResult.Outcome outcomeOf(ReservationStatus s) {
        return switch (s) {
            case CONFIRMED, CANCELLED -> ConfirmResult.Outcome.CONFIRMED;
            case PAYMENT_PENDING -> ConfirmResult.Outcome.PENDING;
            default -> ConfirmResult.Outcome.DECLINED;
        };
    }
}
