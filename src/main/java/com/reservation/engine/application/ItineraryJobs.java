package com.reservation.engine.application;

import com.reservation.engine.config.ReservationProperties;
import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.payment.PaymentPort;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ItineraryRepository;
import com.reservation.engine.persistence.ReservationRepository;
import com.reservation.engine.observability.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Background work for itineraries:
 * <ul>
 *   <li>{@link #sweepExpired()}: claims lapsed HELD itineraries with FOR UPDATE SKIP LOCKED and expires the itinerary
 *       and all items together (items are excluded from the per-reservation sweeper).</li>
 *   <li>{@link #reconcile(Duration)}: resolves itineraries stuck in PAYMENT_PENDING via the provider's status API.
 *       Itinerary refunds are picked up by {@link PaymentReconciliationJob} (it processes every unresolved refund).</li>
 * </ul>
 */
@Component
public class ItineraryJobs {
    private static final Logger log = LoggerFactory.getLogger(ItineraryJobs.class);
    private static final int MAX_BATCHES_PER_RUN = 50;
    private static final int RECONCILE_BATCH = 100;

    public record ReconcileReport(int resolved, int stillUnknown) {
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
    private final int batchSize;
    private final Duration reconcileAfter;

    public ItineraryJobs(ItineraryRepository itineraries, ReservationRepository reservations,
                         PaymentTransactionRepository payments, PaymentPort paymentPort,
                         ItineraryPaymentFinalizer finalizer, ItineraryLifecycle lifecycle,
                         ItineraryEventRecorder recorder, ReservationMetrics metrics, TransactionTemplate tx,
                         Clock clock, ReservationProperties props) {
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
        this.batchSize = props.expiry().batchSize();
        this.reconcileAfter = props.payment().reconcileAfter();
    }

    @Scheduled(fixedDelayString = "${reservation.expiry.sweep-interval:PT15S}")
    public void scheduledSweep() {
        int n = sweepExpired();
        if (n > 0) {
            log.info("itinerary expiry sweep expired={}", n);
        }
    }

    @Scheduled(fixedDelayString = "${reservation.payment.reconcile-interval:PT30S}")
    public void scheduledReconcile() {
        ReconcileReport r = reconcile(reconcileAfter);
        if (r.resolved() + r.stillUnknown() > 0) {
            log.info("itinerary payment reconciliation {}", r);
        }
    }

    public int sweepExpired() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            Integer n = tx.execute(s -> {
                Instant now = clock.instant();
                List<Itinerary> batch = itineraries.lockExpiredHolds(now, batchSize);
                for (Itinerary it : batch) {
                    List<Reservation> items = reservations.findByItineraryIdForUpdate(it.getId());
                    lifecycle.expire(it, items, "system", "hold expired", now);
                    recorder.record(it, items);
                }
                metrics.itineraryExpired(batch.size());
                return batch.size();
            });
            if (n == null || n == 0) {
                break;
            }
            total += n;
            if (n < batchSize) {
                break;
            }
        }
        return total;
    }

    public ReconcileReport reconcile(Duration olderThan) {
        Instant before = clock.instant().minus(olderThan);
        int resolved = 0;
        int unknown = 0;
        List<UUID> stuck = tx.execute(s -> itineraries.findStalePaymentPending(before, RECONCILE_BATCH));
        for (UUID id : stuck == null ? List.<UUID>of() : stuck) {
            PaymentTransaction charge = tx.execute(s -> payments.findLatestUnresolvedItineraryCharge(id).orElse(null));
            if (charge == null) {
                log.warn("PAYMENT_PENDING itinerary without unresolved charge id={}", id);
                continue;
            }
            PaymentPort.PaymentResult result;
            try {
                result = paymentPort.status(charge.getId().toString());
            } catch (RuntimeException e) {
                result = PaymentPort.PaymentResult.unknown(e.getMessage());
            }
            if (result.outcome() == PaymentPort.Outcome.UNKNOWN) {
                unknown++;
                continue;
            }
            finalizer.finalizeCharge(id, charge.getId(), result, "reconciler");
            resolved++;
        }
        return new ReconcileReport(resolved, unknown);
    }
}
