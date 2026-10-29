package com.reservation.engine.idempotency;

import com.reservation.engine.persistence.SqlTime;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.reservation.engine.persistence.SqlTime.ts;

/** Plain-JDBC access; the UNIQUE (user_id, idem_key) constraint is what serialises concurrent retries. */
@Repository
public class IdempotencyRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public IdempotencyRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert-first claim. Returns the new record id, or empty if the key already exists. */
    public Optional<Long> tryClaim(String userId, String key, String operation, String hash, Instant now, Instant expiresAt) {
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO idempotency_record (user_id, idem_key, operation, request_hash, status, created_at, updated_at, expires_at)
                VALUES (:u, :k, :op, :h, 'IN_PROGRESS', :now, :now, :exp)
                ON CONFLICT (user_id, idem_key) DO NOTHING
                RETURNING id
                """, params(userId, key).addValue("op", operation).addValue("h", hash)
                .addValue("now", ts(now)).addValue("exp", ts(expiresAt)), Long.class);
        return ids.stream().findFirst();
    }

    /** Take over an abandoned IN_PROGRESS claim (process crashed) with the same payload. */
    public Optional<Long> reclaimStale(String userId, String key, String hash, Instant staleBefore, Instant now) {
        List<Long> ids = jdbc.queryForList("""
                UPDATE idempotency_record SET updated_at = :now
                 WHERE user_id = :u AND idem_key = :k AND request_hash = :h
                   AND status = 'IN_PROGRESS' AND updated_at < :stale
                RETURNING id
                """, params(userId, key).addValue("h", hash).addValue("stale", ts(staleBefore)).addValue("now", ts(now)),
                Long.class);
        return ids.stream().findFirst();
    }

    public Optional<IdempotencyRecord> find(String userId, String key) {
        return jdbc.query("""
                SELECT id, user_id, idem_key, operation, request_hash, status, response_status,
                       response_body::text AS body, updated_at
                  FROM idempotency_record WHERE user_id = :u AND idem_key = :k
                """, params(userId, key), (rs, i) -> new IdempotencyRecord(
                rs.getLong("id"), rs.getString("user_id"), rs.getString("idem_key"), rs.getString("operation"),
                rs.getString("request_hash"), IdempotencyRecord.Status.valueOf(rs.getString("status")),
                (Integer) rs.getObject("response_status"), rs.getString("body"), SqlTime.instant(rs, "updated_at")))
                .stream().findFirst();
    }

    public void complete(long id, int status, String body, Instant now) {
        jdbc.update("""
                UPDATE idempotency_record SET status = 'COMPLETED', response_status = :s,
                       response_body = CAST(:b AS jsonb), updated_at = :now
                 WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("s", status).addValue("b", body).addValue("now", ts(now)));
    }

    public void release(long id) {
        jdbc.update("DELETE FROM idempotency_record WHERE id = :id AND status = 'IN_PROGRESS'",
                new MapSqlParameterSource("id", id));
    }

    public int deleteExpired(String userId, String key, Instant now) {
        return jdbc.update("DELETE FROM idempotency_record WHERE user_id = :u AND idem_key = :k AND expires_at < :now",
                params(userId, key).addValue("now", ts(now)));
    }

    public int deleteAllExpired(Instant now) {
        return jdbc.update("DELETE FROM idempotency_record WHERE expires_at < :now",
                new MapSqlParameterSource("now", ts(now)));
    }

    private static MapSqlParameterSource params(String userId, String key) {
        return new MapSqlParameterSource("u", userId).addValue("k", key);
    }
}
