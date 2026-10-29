package com.reservation.engine.application;

import com.reservation.engine.config.ReservationProperties;
import com.reservation.engine.payment.PaymentPort;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves uncertain payment outcomes: reservations stuck in PAYMENT_PENDING (charge UNKNOWN / crashed
 * mid-confirm) and refunds queued by cancellations. Provider calls happen outside any transaction;
 * results are applied through {@link PaymentFinalizer}'s guarded transactions.
 */
@Component
public class PaymentReconciliationJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationJob.class);
    private static final int BATCH = 100;

    public record Report(int chargesResolved, int chargesStillUnknown, int refundsProcessed) {
    }

    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final PaymentPort paymentPort;
    private final PaymentFinalizer finalizer;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration reconcileAfter;

    public PaymentReconciliationJob(ReservationRepository reservations, PaymentTransactionRepository payments,
                                    PaymentPort paymentPort, PaymentFinalizer finalizer, TransactionTemplate tx,
                                    Clock clock, ReservationProperties props) {
        this.reservations = reservations;
        this.payments = payments;
        this.paymentPort = paymentPort;
        this.finalizer = finalizer;
        this.tx = tx;
        this.clock = clock;
        this.reconcileAfter = props.payment().reconcileAfter();
    }

    @Scheduled(fixedDelayString = "${reservation.payment.reconcile-interval:PT30S}")
    public void scheduled() {
        Report r = reconcile(reconcileAfter);
        if (r.chargesResolved() + r.chargesStillUnknown() + r.refundsProcessed() > 0) {
            log.info("payment reconciliation {}", r);
        }
    }

    public Report reconcile(Duration olderThan) {
        var before = clock.instant().minus(olderThan);
        int resolved = 0;
        int unknown = 0;
        List<UUID> stuck = tx.execute(s -> reservations.findStalePaymentPending(before, BATCH));
        for (UUID reservationId : stuck == null ? List.<UUID>of() : stuck) {
            Optional<PaymentTransaction> charge = Optional.ofNullable(
                    tx.execute(s -> payments.findLatestUnresolvedCharge(reservationId).orElse(null)));
            if (charge.isEmpty()) {
                log.warn("PAYMENT_PENDING reservation without unresolved charge id={}", reservationId);
                continue;
            }
            PaymentPort.PaymentResult result = safeStatus(charge.get().getId().toString());
            if (result.outcome() == PaymentPort.Outcome.UNKNOWN) {
                unknown++;
                continue;
            }
            finalizer.finalizeCharge(reservationId, charge.get().getId(), result, "reconciler");
            resolved++;
        }

        int refunds = 0;
        List<PaymentTransaction> pendingRefunds = tx.execute(s ->
                payments.findUnresolvedRefunds(clock.instant(), PageRequest.of(0, BATCH)));
        for (PaymentTransaction refund : pendingRefunds == null ? List.<PaymentTransaction>of() : pendingRefunds) {
            PaymentPort.PaymentResult result;
            try {
                result = paymentPort.refund(new PaymentPort.RefundRequest(refund.getId().toString(),
                        refund.getOwnerId(), refund.getAmount()));
            } catch (RuntimeException e) {
                log.warn("refund call failed paymentTx={}", refund.getId(), e);
                result = PaymentPort.PaymentResult.unknown(e.getMessage());
            }
            finalizer.finalizeRefund(refund.getId(), result);
            refunds++;
        }
        return new Report(resolved, unknown, refunds);
    }

    private PaymentPort.PaymentResult safeStatus(String key) {
        try {
            return paymentPort.status(key);
        } catch (RuntimeException e) {
            log.warn("payment status lookup failed key={}", key, e);
            return PaymentPort.PaymentResult.unknown(e.getMessage());
        }
    }
}
