package com.reservation.engine.inventory.capacity;

import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.persistence.SavepointTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Hot-inventory mode for CAPACITY: the counter is split into N {@code capacity_bucket} rows so concurrent holds
 * update different rows instead of queueing on one row lock. Invariants enforced by the database:
 * per bucket {@code CHECK (reserved <= capacity)} and (deferred trigger) {@code sum(bucket.capacity) = capacity},
 * hence the total can never be oversold.
 *
 * <p>Allocation, cheapest first:
 * <ol>
 *   <li><b>Non-blocking fit</b>: one statement picks the first bucket - rotating from a random start - that fits
 *       the whole quantity and is not locked by another transaction ({@code FOR UPDATE SKIP LOCKED}) and
 *       increments it. It never waits.</li>
 *   <li><b>Blocking fit</b>: all fitting buckets were busy; wait on them one by one (ascending bucket order)
 *       with the plain conditional update.</li>
 *   <li><b>Split</b>: no single bucket fits; take {@code LEAST(need, free)} from buckets in ascending bucket order.
 *       Fails fast without locking when the total free quantity is insufficient.</li>
 * </ol>
 * <p>Deadlock freedom: PostgreSQL keeps a row lock even when the locked row then fails the statement's re-check
 * (READ COMMITTED). Steps 1 and 2 therefore run inside savepoints that are rolled back on a miss, releasing such
 * stray locks; every step that WAITS acquires bucket locks in ascending bucket order only. (Found the hard way:
 * the first version deadlocked under the concurrency test.)
 * Returns bucket -> quantity taken so release can give back to the exact source buckets.
 */
class ShardedCapacityCounter {

    private final NamedParameterJdbcTemplate jdbc;
    private final SavepointTemplate savepoints;

    ShardedCapacityCounter(NamedParameterJdbcTemplate jdbc, SavepointTemplate savepoints) {
        this.jdbc = jdbc;
        this.savepoints = savepoints;
    }

    void provision(UUID resourceId, int capacity, int buckets) {
        List<MapSqlParameterSource> rows = new ArrayList<>(buckets);
        for (int b = 0; b < buckets; b++) {
            rows.add(new MapSqlParameterSource("r", resourceId).addValue("b", b)
                    .addValue("c", share(capacity, buckets, b)));
        }
        jdbc.batchUpdate("INSERT INTO capacity_bucket (resource_id, bucket_no, capacity, reserved) VALUES (:r, :b, :c, 0)",
                rows.toArray(MapSqlParameterSource[]::new));
    }

    /** Capacity top-up: spread the extra capacity over the buckets (caller already updated the parent row). */
    void addCapacity(UUID resourceId, int extra, int buckets) {
        List<MapSqlParameterSource> rows = new ArrayList<>(buckets);
        for (int b = 0; b < buckets; b++) {
            int add = share(extra, buckets, b);
            if (add > 0) {
                rows.add(new MapSqlParameterSource("r", resourceId).addValue("b", b).addValue("c", add));
            }
        }
        jdbc.batchUpdate("UPDATE capacity_bucket SET capacity = capacity + :c WHERE resource_id = :r AND bucket_no = :b",
                rows.toArray(MapSqlParameterSource[]::new));
    }

    Map<Integer, Integer> allocate(UUID resourceId, int quantity, int buckets) {
        int start = ThreadLocalRandom.current().nextInt(buckets);
        MapSqlParameterSource p = new MapSqlParameterSource("r", resourceId).addValue("q", quantity)
                .addValue("start", start).addValue("n", buckets);

        Optional<Integer> fast = savepoints.keepIfPresent(() -> jdbc.queryForList("""
                UPDATE capacity_bucket SET reserved = reserved + :q
                 WHERE resource_id = :r AND reserved + :q <= capacity
                   AND bucket_no = (SELECT bucket_no FROM capacity_bucket
                                     WHERE resource_id = :r AND reserved + :q <= capacity
                                     ORDER BY (bucket_no - :start + :n) % :n
                                     LIMIT 1
                                     FOR UPDATE SKIP LOCKED)
                RETURNING bucket_no
                """, p, Integer.class).stream().findFirst());
        if (fast.isPresent()) {
            return Map.of(fast.get(), quantity);
        }

        Optional<Integer> blocking = savepoints.keepIfPresent(() -> {
            List<Integer> candidates = jdbc.queryForList("""
                    SELECT bucket_no FROM capacity_bucket
                     WHERE resource_id = :r AND reserved + :q <= capacity
                     ORDER BY bucket_no
                    """, p, Integer.class);
            for (Integer b : candidates) {
                int updated = jdbc.update("""
                        UPDATE capacity_bucket SET reserved = reserved + :q
                         WHERE resource_id = :r AND bucket_no = :b AND reserved + :q <= capacity
                        """, new MapSqlParameterSource(p.getValues()).addValue("b", b));
                if (updated == 1) {
                    return Optional.of(b);
                }
            }
            return Optional.empty();
        });
        if (blocking.isPresent()) {
            return Map.of(blocking.get(), quantity);
        }
        return split(resourceId, quantity);
    }

    private Map<Integer, Integer> split(UUID resourceId, int quantity) {
        MapSqlParameterSource r = new MapSqlParameterSource("r", resourceId);
        Integer free = jdbc.queryForObject(
                "SELECT COALESCE(SUM(capacity - reserved), 0) FROM capacity_bucket WHERE resource_id = :r", r, Integer.class);
        if (free == null || free < quantity) {
            throw unavailable(resourceId, quantity);
        }
        List<Integer> withFree = jdbc.queryForList("""
                SELECT bucket_no FROM capacity_bucket WHERE resource_id = :r AND reserved < capacity ORDER BY bucket_no
                """, r, Integer.class);
        Map<Integer, Integer> taken = new LinkedHashMap<>();
        int need = quantity;
        for (Integer b : withFree) {
            List<Integer> got = jdbc.queryForList("""
                    WITH t AS (SELECT bucket_no, LEAST(:need, capacity - reserved) AS take
                                 FROM capacity_bucket WHERE resource_id = :r AND bucket_no = :b FOR UPDATE)
                    UPDATE capacity_bucket c SET reserved = c.reserved + t.take
                      FROM t
                     WHERE c.resource_id = :r AND c.bucket_no = t.bucket_no AND t.take > 0
                    RETURNING t.take
                    """, new MapSqlParameterSource("r", resourceId).addValue("b", b).addValue("need", need), Integer.class);
            if (!got.isEmpty()) {
                taken.put(b, got.get(0));
                need -= got.get(0);
                if (need == 0) {
                    return taken;
                }
            }
        }
        // consumed concurrently; the caller's transaction rolls back every partial take
        throw unavailable(resourceId, quantity);
    }

    void release(UUID resourceId, int bucketNo, int quantity) {
        jdbc.update("UPDATE capacity_bucket SET reserved = reserved - :q WHERE resource_id = :r AND bucket_no = :b",
                new MapSqlParameterSource("r", resourceId).addValue("b", bucketNo).addValue("q", quantity));
    }

    int[] totals(UUID resourceId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(capacity), 0) AS c, COALESCE(SUM(reserved), 0) AS r
                  FROM capacity_bucket WHERE resource_id = :r
                """, new MapSqlParameterSource("r", resourceId), (rs, i) -> new int[]{rs.getInt("c"), rs.getInt("r")});
    }

    /** Even split; the first {@code total % buckets} buckets get one extra. */
    static int share(int total, int buckets, int bucketNo) {
        return total / buckets + (bucketNo < total % buckets ? 1 : 0);
    }

    private static InventoryUnavailableException unavailable(UUID resourceId, int quantity) {
        return new InventoryUnavailableException("Not enough capacity for resource " + resourceId
                + " (requested " + quantity + ")");
    }
}
