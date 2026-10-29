package com.reservation.engine.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Re-runs a WHOLE transaction (the supplier must open and commit its own transaction) when PostgreSQL aborts it
 * with a deadlock (40P01) or lock timeout (55P03). Both are translated by Spring into
 * {@link PessimisticLockingFailureException}. Retrying is safe because the aborted attempt left no effects.
 */
public final class LockConflictRetry {
    private static final Logger log = LoggerFactory.getLogger(LockConflictRetry.class);

    private static final java.util.concurrent.atomic.AtomicLong RETRIES = new java.util.concurrent.atomic.AtomicLong();

    private LockConflictRetry() {
    }

    /** Number of retries performed by this JVM so far (reported by the benchmark harness). */
    public static long retriesSoFar() {
        return RETRIES.get();
    }

    public static <T> T run(int attempts, String operation, Supplier<T> transaction) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= attempts) {
                    throw e;
                }
                RETRIES.incrementAndGet();
                log.info("lock conflict, retrying operation={} attempt={} cause={}", operation, attempt,
                        e.getClass().getSimpleName());
                backoff(attempt);
            }
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5L * attempt, 25L * attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
