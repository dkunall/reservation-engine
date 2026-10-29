package com.reservation.engine.application;

import com.reservation.engine.config.ReservationProperties;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.persistence.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Expires lapsed holds in small batches. Rows are claimed with FOR UPDATE SKIP LOCKED: a reservation locked
 * by a concurrent confirm/cancel is skipped (and will no longer be HELD afterwards), so the sweeper and
 * confirm can never both win. Only HELD rows qualify - PAYMENT_PENDING is left to reconciliation.
 */
@Component
public class ExpirySweeper {
    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);
    private static final int MAX_BATCHES_PER_RUN = 50;

    private final ReservationRepository reservations;
    private final InventoryAllocator inventory;
    private final ReservationEventRecorder recorder;
    private final ReservationMetrics metrics;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;

    public ExpirySweeper(ReservationRepository reservations, InventoryAllocator inventory,
                         ReservationEventRecorder recorder, ReservationMetrics metrics, TransactionTemplate tx,
                         Clock clock, ReservationProperties props) {
        this.reservations = reservations;
        this.inventory = inventory;
        this.recorder = recorder;
        this.metrics = metrics;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = props.expiry().batchSize();
    }

    @Scheduled(fixedDelayString = "${reservation.expiry.sweep-interval:PT15S}")
    public void scheduled() {
        int n = sweep();
        if (n > 0) {
            log.info("expiry sweep expired={}", n);
        }
    }

    /** @return number of reservations expired */
    public int sweep() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            Integer n = tx.execute(status -> sweepBatch());
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

    private int sweepBatch() {
        Instant now = clock.instant();
        List<Reservation> batch = reservations.lockExpiredHolds(now, batchSize);
        for (Reservation r : batch) {
            r.expire("system", "hold expired", now);
            inventory.release(r, now);
            recorder.record(r);
        }
        Map<String, Long> byType = batch.stream()
                .collect(Collectors.groupingBy(Reservation::getResourceType, Collectors.counting()));
        byType.forEach((type, count) -> metrics.expired(type, count.intValue()));
        return batch.size();
    }

}
