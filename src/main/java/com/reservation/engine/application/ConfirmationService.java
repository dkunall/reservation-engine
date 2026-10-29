package com.reservation.engine.application;

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
import com.reservation.engine.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Confirm = three steps, and NO database transaction is open during the payment call:
 * <ol>
 *   <li>tx#1 (row lock): HELD -> PAYMENT_PENDING, create PENDING charge record. Commit.</li>
 *   <li>call {@link PaymentPort#charge} (idempotent on the charge id).</li>
 *   <li>tx#2 ({@link PaymentFinalizer}): apply outcome. UNKNOWN outcomes are left for the reconciliation job.</li>
 * </ol>
 * PAYMENT_PENDING reservations are never touched by the expiry sweeper, so a slow payment cannot lose its inventory.
 */
@Service
public class ConfirmationService {
    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

    private enum StartKind { ALREADY_CONFIRMED, CONFIRMED_WITHOUT_PAYMENT, EXPIRED, PAYMENT_REQUIRED }

    private record Start(StartKind kind, ReservationView view, UUID paymentTxId, Money amount) {
    }

    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final PaymentPort paymentPort;
    private final PaymentFinalizer finalizer;
    private final InventoryAllocator inventory;
    private final ReservationEventRecorder recorder;
    private final ReservationMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ConfirmationService(ReservationRepository reservations, PaymentTransactionRepository payments,
                               PaymentPort paymentPort, PaymentFinalizer finalizer, InventoryAllocator inventory,
                               ReservationEventRecorder recorder, ReservationMetrics metrics,
                               TransactionTemplate tx, Clock clock) {
        this.reservations = reservations;
        this.payments = payments;
        this.paymentPort = paymentPort;
        this.finalizer = finalizer;
        this.inventory = inventory;
        this.recorder = recorder;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
    }

    public ConfirmResult confirm(CurrentUser user, UUID reservationId, String paymentMethodToken) {
        return metrics.time("reservation.confirm.duration", () -> doConfirm(user, reservationId, paymentMethodToken));
    }

    private ConfirmResult doConfirm(CurrentUser user, UUID reservationId, String paymentMethodToken) {
        Start start = tx.execute(status -> begin(user, reservationId));
        switch (start.kind()) {
            case ALREADY_CONFIRMED, CONFIRMED_WITHOUT_PAYMENT -> {
                return new ConfirmResult(start.view(), ConfirmResult.Outcome.CONFIRMED);
            }
            case EXPIRED -> {
                metrics.confirmOutcome(start.view().resourceType(), "expired");
                throw new HoldExpiredException("Hold for reservation " + reservationId + " has expired");
            }
            default -> {
                // fall through to payment
            }
        }

        PaymentPort.PaymentResult result;
        try {
            result = paymentPort.charge(new PaymentPort.ChargeRequest(start.paymentTxId().toString(), reservationId,
                    start.amount(), paymentMethodToken));
        } catch (RuntimeException e) {
            log.warn("payment call failed; outcome unknown reservation={} paymentTx={}", reservationId, start.paymentTxId(), e);
            result = PaymentPort.PaymentResult.unknown("provider error: " + e.getMessage());
        }

        ConfirmResult outcome = finalizer.finalizeCharge(reservationId, start.paymentTxId(), result, user.userId());
        if (outcome.outcome() == ConfirmResult.Outcome.DECLINED) {
            throw new PaymentDeclinedException("Payment declined for reservation " + reservationId
                    + "; reservation is now " + outcome.reservation().status());
        }
        return outcome;
    }

    private Start begin(CurrentUser user, UUID reservationId) {
        Reservation r = reservations.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation " + reservationId + " not found"));
        AccessPolicy.assertCanAccess(user, r);
        ItineraryGuard.assertStandalone(r);
        Instant now = clock.instant();

        if (r.getStatus() == ReservationStatus.CONFIRMED) {
            return new Start(StartKind.ALREADY_CONFIRMED, ReservationView.from(r), null, null);
        }
        if (r.getStatus() == ReservationStatus.HELD && r.isHoldExpired(now)) {
            // expire eagerly (committed) instead of waiting for the sweeper
            r.expire("system", "hold expired before confirmation", now);
            inventory.release(r, now);
            recorder.record(r);
            return new Start(StartKind.EXPIRED, ReservationView.from(r), null, null);
        }
        if (r.getStatus() == ReservationStatus.HELD && r.getTotal().isZero()) {
            r.confirm(user.userId(), now);
            recorder.record(r);
            metrics.confirmOutcome(r.getResourceType(), "confirmed");
            return new Start(StartKind.CONFIRMED_WITHOUT_PAYMENT, ReservationView.from(r), null, null);
        }
        r.startPayment(user.userId(), now); // throws InvalidStateTransition for PAYMENT_PENDING / terminal states
        PaymentTransaction charge = payments.save(
                new PaymentTransaction(r.getId(), PaymentTransaction.Type.CHARGE, r.getTotal(), now));
        recorder.record(r);
        return new Start(StartKind.PAYMENT_REQUIRED, ReservationView.from(r), charge.getId(), r.getTotal());
    }
}
