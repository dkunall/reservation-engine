package com.reservation.engine.waitlist;

import com.reservation.engine.inventory.spi.InventoryReleaseListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.reservation.engine.persistence.SqlTime.ts;

/**
 * Transactional "inventory may be available" signals (a tiny outbox dedicated to the waitlist).
 * Written in the SAME transaction as the release, so a committed release always leaves a signal behind and a
 * rolled-back release never does. Consumed by {@link WaitlistPromoter} with FOR UPDATE SKIP LOCKED.
 */
@Component
public class WaitlistSignals implements InventoryReleaseListener {

    public record Claimed(long id, UUID resourceId) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public WaitlistSignals(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Only writes a signal when someone is actually waiting, keeping releases without a waitlist at zero cost. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onRelease(UUID resourceId, UUID reservationId, String inventoryModel, Instant at) {
        jdbc.update("""
                INSERT INTO waitlist_signal (resource_id, reason, created_at)
                SELECT :r, 'RELEASED', :now
                 WHERE EXISTS (SELECT 1 FROM waitlist_entry WHERE resource_id = :r AND status = 'WAITING')
                """, new MapSqlParameterSource("r", resourceId).addValue("now", ts(at)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void signal(UUID resourceId, String reason, Instant at) {
        jdbc.update("INSERT INTO waitlist_signal (resource_id, reason, created_at) VALUES (:r, :reason, :now)",
                new MapSqlParameterSource("r", resourceId).addValue("reason", reason).addValue("now", ts(at)));
    }

    /**
     * Claims the oldest pending signal not locked by another promoter, then marks it - and every other pending,
     * unlocked signal for the same resource - processed (coalescing). Must run in the caller's transaction so
     * the "processed" mark commits only after the promotions triggered by it were attempted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Claimed> claimNext(Instant now) {
        List<Claimed> rows = jdbc.query("""
                SELECT id, resource_id FROM waitlist_signal
                 WHERE processed_at IS NULL
                 ORDER BY id
                 LIMIT 1
                 FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource(),
                (rs, i) -> new Claimed(rs.getLong("id"), rs.getObject("resource_id", UUID.class)));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Claimed claimed = rows.get(0);
        jdbc.update("""
                UPDATE waitlist_signal SET processed_at = :now
                 WHERE id IN (SELECT id FROM waitlist_signal
                               WHERE resource_id = :r AND processed_at IS NULL
                               FOR UPDATE SKIP LOCKED)
                """, new MapSqlParameterSource("r", claimed.resourceId()).addValue("now", ts(now)));
        return Optional.of(claimed);
    }

    public int pendingCount() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM waitlist_signal WHERE processed_at IS NULL",
                new MapSqlParameterSource(), Integer.class);
        return n == null ? 0 : n;
    }

    /** Housekeeping: processed signals older than the cutoff are useless. */
    public int purgeProcessed(Instant before) {
        return jdbc.update("DELETE FROM waitlist_signal WHERE processed_at IS NOT NULL AND processed_at < :b",
                new MapSqlParameterSource("b", ts(before)));
    }
}
