package com.reservation.engine.inventory.capacity;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.AllocationResult;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.inventory.spi.AvailabilityQuery;
import com.reservation.engine.inventory.spi.AvailabilityView;
import com.reservation.engine.inventory.spi.InventoryModels;
import com.reservation.engine.inventory.spi.InventorySetup;
import com.reservation.engine.inventory.spi.InventoryStrategy;
import com.reservation.engine.persistence.SavepointTemplate;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.reservation.engine.persistence.SqlTime.ts;

/**
 * CAPACITY model. Two counter layouts, chosen per resource at first provisioning:
 * <ul>
 *   <li><b>single row</b> (default): allocation is one atomic conditional UPDATE ({@code reserved + q <= capacity});
 *       the CHECK constraint is the last line of defence against overselling. The row lock taken by the UPDATE
 *       is held only for the short hold transaction.</li>
 *   <li><b>sharded</b> ({@code buckets = N}): N bucket rows, see {@link ShardedCapacityCounter}. Availability is
 *       the sum of the buckets; release goes back to the source bucket recorded on the allocation row.</li>
 * </ul>
 * The bucket count is immutable once provisioned, so it is cached per resource (safe across instances).
 */
@Component
public class CapacityInventoryStrategy implements InventoryStrategy {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final ShardedCapacityCounter sharded;
    private final Map<UUID, Integer> bucketCounts = new ConcurrentHashMap<>();

    public CapacityInventoryStrategy(NamedParameterJdbcTemplate jdbc, Clock clock, SavepointTemplate savepoints) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.sharded = new ShardedCapacityCounter(jdbc, savepoints);
    }

    @Override
    public String model() {
        return InventoryModels.CAPACITY;
    }

    @Override
    public void addInventory(Resource resource, InventorySetup setup) {
        if (setup.capacity() == null || setup.capacity() < 0) {
            throw new InvalidRequestException("CAPACITY inventory requires a non-negative 'capacity'");
        }
        if (setup.buckets() != null && (setup.buckets() < 1 || setup.buckets() > 1024)) {
            throw new InvalidRequestException("'buckets' must be between 1 and 1024");
        }
        UUID id = resource.getId();
        MapSqlParameterSource p = new MapSqlParameterSource("r", id).addValue("c", setup.capacity());
        List<Integer> existing = jdbc.queryForList(
                "SELECT bucket_count FROM capacity_inventory WHERE resource_id = :r FOR UPDATE", p, Integer.class);
        if (existing.isEmpty()) {
            int buckets = setup.buckets() == null ? 0 : setup.buckets();
            int inserted = jdbc.update("""
                    INSERT INTO capacity_inventory (resource_id, capacity, reserved, bucket_count)
                    VALUES (:r, :c, 0, :b) ON CONFLICT (resource_id) DO NOTHING
                    """, p.addValue("b", buckets));
            if (inserted == 0) { // concurrent first provisioning; retry as a top-up
                addInventory(resource, setup);
                return;
            }
            if (buckets > 0) {
                sharded.provision(id, setup.capacity(), buckets);
            }
            return;
        }
        int buckets = existing.get(0);
        if (setup.buckets() != null && setup.buckets() != buckets) {
            throw new InvalidRequestException("Bucket layout is fixed at first provisioning (currently "
                    + buckets + " buckets)");
        }
        jdbc.update("UPDATE capacity_inventory SET capacity = capacity + :c WHERE resource_id = :r", p);
        if (buckets > 0) {
            sharded.addCapacity(id, setup.capacity(), buckets);
        }
    }

    @Override
    public void validateRequest(Resource resource, AllocationRequest request) {
        if (request.quantity() < 1) {
            throw new InvalidRequestException("quantity must be >= 1");
        }
        if (request.hasUnitCodes() || request.slotId() != null) {
            throw new InvalidRequestException("CAPACITY resources do not accept unitCodes or slotId");
        }
    }

    @Override
    public long billableUnits(AllocationRequest request, ResourceTypeDefinition type) {
        return request.quantity();
    }

    @Override
    public AllocationResult allocate(Resource resource, UUID reservationId, AllocationRequest request) {
        int buckets = bucketCount(resource.getId());
        MapSqlParameterSource p = new MapSqlParameterSource("r", resource.getId()).addValue("res", reservationId)
                .addValue("now", ts(clock.instant()));
        if (buckets > 0) {
            Map<Integer, Integer> taken = sharded.allocate(resource.getId(), request.quantity(), buckets);
            List<MapSqlParameterSource> rows = new ArrayList<>(taken.size());
            taken.forEach((bucket, q) -> rows.add(new MapSqlParameterSource(p.getValues())
                    .addValue("id", UUID.randomUUID()).addValue("q", q).addValue("b", bucket)));
            jdbc.batchUpdate("""
                    INSERT INTO capacity_allocation (id, reservation_id, resource_id, quantity, status, created_at, bucket_no)
                    VALUES (:id, :res, :r, :q, 'ACTIVE', :now, :b)
                    """, rows.toArray(MapSqlParameterSource[]::new));
            return AllocationResult.ofQuantity(request.quantity());
        }
        p.addValue("q", request.quantity());
        int updated = jdbc.update("""
                UPDATE capacity_inventory SET reserved = reserved + :q
                 WHERE resource_id = :r AND reserved + :q <= capacity
                """, p);
        if (updated == 0) {
            throw new InventoryUnavailableException("Not enough capacity for resource " + resource.getId()
                    + " (requested " + request.quantity() + ")");
        }
        jdbc.update("""
                INSERT INTO capacity_allocation (id, reservation_id, resource_id, quantity, status, created_at)
                VALUES (:id, :res, :r, :q, 'ACTIVE', :now)
                """, p.addValue("id", UUID.randomUUID()));
        return AllocationResult.ofQuantity(request.quantity());
    }

    @Override
    public void release(UUID reservationId, Instant now) {
        // conditional status flip makes release idempotent; only rows actually flipped give capacity back
        List<Object[]> released = jdbc.query("""
                UPDATE capacity_allocation SET status = 'RELEASED', released_at = :now
                 WHERE reservation_id = :res AND status = 'ACTIVE'
                RETURNING resource_id, quantity, bucket_no
                """, new MapSqlParameterSource("res", reservationId).addValue("now", ts(now)),
                (rs, i) -> new Object[]{rs.getObject("resource_id", UUID.class), rs.getInt("quantity"),
                        rs.getObject("bucket_no", Integer.class)});
        // ascending bucket order: the same lock order as every waiting allocation step
        released.sort(java.util.Comparator.comparing(row -> row[2] == null ? -1 : (Integer) row[2]));
        for (Object[] row : released) {
            if (row[2] != null) {
                sharded.release((UUID) row[0], (Integer) row[2], (Integer) row[1]);
            } else {
                jdbc.update("UPDATE capacity_inventory SET reserved = reserved - :q WHERE resource_id = :r",
                        new MapSqlParameterSource("r", row[0]).addValue("q", row[1]));
            }
        }
    }

    @Override
    public AvailabilityView availability(Resource resource, AvailabilityQuery query) {
        List<int[]> rows = bucketCount(resource.getId()) > 0
                ? List.of(sharded.totals(resource.getId()))
                : jdbc.query("SELECT capacity, reserved FROM capacity_inventory WHERE resource_id = :r",
                new MapSqlParameterSource("r", resource.getId()),
                (rs, i) -> new int[]{rs.getInt("capacity"), rs.getInt("reserved")});
        int capacity = rows.isEmpty() ? 0 : rows.get(0)[0];
        int available = rows.isEmpty() ? 0 : rows.get(0)[0] - rows.get(0)[1];
        return new AvailabilityView(resource.getId(), model(), available >= Math.max(1, query.quantity()),
                capacity, available, List.of(), List.of());
    }

    /** 0 = single-row layout (also when not provisioned yet; not cached in that case). */
    int bucketCount(UUID resourceId) {
        Integer cached = bucketCounts.get(resourceId);
        if (cached != null) {
            return cached;
        }
        List<Integer> rows = jdbc.queryForList("SELECT bucket_count FROM capacity_inventory WHERE resource_id = :r",
                new MapSqlParameterSource("r", resourceId), Integer.class);
        if (rows.isEmpty()) {
            return 0;
        }
        bucketCounts.put(resourceId, rows.get(0));
        return rows.get(0);
    }

    /** Counter row(s) are the most contended locks: take them last in multi-item transactions. */
    @Override
    public int lockOrderRank() {
        return 1;
    }
}
