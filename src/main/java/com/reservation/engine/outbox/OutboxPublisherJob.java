package com.reservation.engine.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;

/**
 * Polls PENDING events (FOR UPDATE SKIP LOCKED, so multiple instances don't double-publish concurrently)
 * and marks them SENT. Delivery is at-least-once: a crash after publish but before commit republishes.
 */
@Component
public class OutboxPublisherJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherJob.class);
    private static final int MAX_ATTEMPTS = 10;

    private final OutboxRepository repository;
    private final EventPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;

    public OutboxPublisherJob(OutboxRepository repository, EventPublisher publisher, TransactionTemplate tx, Clock clock,
                              com.reservation.engine.config.ReservationProperties props) {
        this.repository = repository;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = props.outbox().batchSize();
    }

    @Scheduled(fixedDelayString = "${reservation.outbox.poll-interval:PT2S}")
    public void scheduled() {
        publishPending();
    }

    private record BatchResult(int fetched, int sent) {
    }

    /** @return number of events published in this run (bounded number of batches). */
    public int publishPending() {
        int total = 0;
        for (int round = 0; round < 20; round++) {
            BatchResult r = tx.execute(status -> {
                List<OutboxEvent> batch = repository.lockPending(batchSize);
                int sent = 0;
                for (OutboxEvent e : batch) {
                    try {
                        publisher.publish(e);
                        e.markSent(clock.instant());
                        sent++;
                    } catch (RuntimeException ex) {
                        log.warn("outbox publish failed id={} type={}", e.getId(), e.getEventType(), ex);
                        e.markAttemptFailed(ex.getMessage(), MAX_ATTEMPTS);
                    }
                }
                return new BatchResult(batch.size(), sent);
            });
            if (r == null) {
                break;
            }
            total += r.sent();
            if (r.fetched() < batchSize) {
                break;
            }
        }
        return total;
    }
}
