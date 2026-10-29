package com.reservation.engine.it;

import com.reservation.engine.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression test for a defect found by the load benchmark: a PostgreSQL lock timeout (55P03) raised by a
 * JdbcTemplate statement used to surface as UncategorizedSQLException (HTTP 500, not retried).
 */
class LockTimeoutTranslationIT extends AbstractPostgresIT {

    @Autowired
    TransactionTemplate tx;

    /** Runs {@code body} while another transaction holds FOR UPDATE on the resource's capacity row. */
    private void whileRowLocked(UUID resource, ThrowingRunnable body) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            jdbc.queryForObject("SELECT reserved FROM capacity_inventory WHERE resource_id = ? FOR UPDATE",
                    Integer.class, resource);
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            body.run();
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void jdbcTemplateLockTimeoutIsAPessimisticLockingFailure() throws Exception {
        UUID r = createCapacityResource(5, "10.00");
        whileRowLocked(r, () -> assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL lock_timeout = '200ms'");
            jdbc.queryForObject("SELECT reserved FROM capacity_inventory WHERE resource_id = ? FOR UPDATE",
                    Integer.class, r);
        })).isInstanceOf(CannotAcquireLockException.class).isInstanceOf(PessimisticLockingFailureException.class));
    }

    @Test
    void holdBlockedPastLockTimeoutAnswers409NotA500() throws Exception {
        UUID r = createCapacityResource(5, "10.00");
        whileRowLocked(r, () -> hold("alice", key(), Map.of("resourceId", r, "quantity", 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION")));
        assertThat(count("SELECT count(*) FROM reservation WHERE resource_id = ?", r)).isZero();
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
