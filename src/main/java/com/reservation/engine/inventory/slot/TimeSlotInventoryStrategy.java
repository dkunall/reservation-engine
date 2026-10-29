package com.reservation.engine.inventory.slot;

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
import com.reservation.engine.persistence.SqlTime;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.reservation.engine.persistence.SqlTime.ts;

/**
 * TIME_SLOT model (appointments, timed-entry tickets): pre-defined slots each with a capacity counter.
 * Same atomic conditional UPDATE technique as CAPACITY, scoped to one slot row; slots in the past
 * cannot be booked.
 */
@Component
public class TimeSlotInventoryStrategy implements InventoryStrategy {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public TimeSlotInventoryStrategy(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public String model() {
        return InventoryModels.TIME_SLOT;
    }

    @Override
    public void addInventory(Resource resource, InventorySetup setup) {
        if (setup.slots().isEmpty()) {
            throw new InvalidRequestException("TIME_SLOT inventory requires non-empty 'slots'");
        }
        for (InventorySetup.SlotSpec s : setup.slots()) {
            if (s.startsAt() == null || s.endsAt() == null || !s.endsAt().isAfter(s.startsAt())) {
                throw new InvalidRequestException("each slot needs startsAt < endsAt");
            }
            if (s.capacity() < 1) {
                throw new InvalidRequestException("slot capacity must be >= 1");
            }
        }
        MapSqlParameterSource[] batch = setup.slots().stream()
                .map(s -> new MapSqlParameterSource("id", UUID.randomUUID()).addValue("r", resource.getId())
                        .addValue("s", ts(s.startsAt())).addValue("e", ts(s.endsAt())).addValue("c", s.capacity()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO time_slot (id, resource_id, starts_at, ends_at, capacity, reserved)
                VALUES (:id, :r, :s, :e, :c, 0) ON CONFLICT (resource_id, starts_at) DO NOTHING
                """, batch);
    }

    @Override
    public void validateRequest(Resource resource, AllocationRequest request) {
        if (request.quantity() < 1) {
            throw new InvalidRequestException("quantity must be >= 1");
        }
        if (request.slotId() == null && request.startsAt() == null) {
            throw new InvalidRequestException("TIME_SLOT resources require slotId or startsAt");
        }
        if (request.hasUnitCodes()) {
            throw new InvalidRequestException("TIME_SLOT resources do not accept unitCodes");
        }
    }

    @Override
    public long billableUnits(AllocationRequest request, ResourceTypeDefinition type) {
        return request.quantity();
    }

    @Override
    public AllocationResult allocate(Resource resource, UUID reservationId, AllocationRequest request) {
        MapSqlParameterSource p = new MapSqlParameterSource("r", resource.getId())
                .addValue("slot", request.slotId())
                .addValue("start", ts(request.startsAt()))
                .addValue("q", request.quantity())
                .addValue("now", ts(clock.instant()));
        String slotFilter = request.slotId() != null ? "id = :slot" : "starts_at = CAST(:start AS timestamptz)";
        List<Object[]> rows = jdbc.query("""
                UPDATE time_slot SET reserved = reserved + :q
                 WHERE resource_id = :r AND %s AND reserved + :q <= capacity AND starts_at > :now
                RETURNING id, starts_at, ends_at
                """.formatted(slotFilter), p,
                (rs, i) -> new Object[]{rs.getObject("id", UUID.class), SqlTime.instant(rs, "starts_at"), SqlTime.instant(rs, "ends_at")});
        if (rows.isEmpty()) {
            Integer exists = jdbc.queryForObject(
                    "SELECT count(*) FROM time_slot WHERE resource_id = :r AND " + slotFilter, p, Integer.class);
            if (exists == null || exists == 0) {
                throw new InvalidRequestException("Unknown slot for resource " + resource.getId());
            }
            throw new InventoryUnavailableException("Slot is full or already started");
        }
        Object[] slot = rows.get(0);
        jdbc.update("""
                INSERT INTO slot_allocation (id, reservation_id, slot_id, quantity, status, created_at)
                VALUES (:id, :res, :slotId, :q, 'ACTIVE', :now)
                """, p.addValue("id", UUID.randomUUID()).addValue("res", reservationId).addValue("slotId", slot[0]));
        return new AllocationResult(request.quantity(), List.of(), (UUID) slot[0], (Instant) slot[1], (Instant) slot[2]);
    }

    @Override
    public void release(UUID reservationId, Instant now) {
        List<Object[]> released = jdbc.query("""
                UPDATE slot_allocation SET status = 'RELEASED', released_at = :now
                 WHERE reservation_id = :res AND status = 'ACTIVE'
                RETURNING slot_id, quantity
                """, new MapSqlParameterSource("res", reservationId).addValue("now", ts(now)),
                (rs, i) -> new Object[]{rs.getObject("slot_id", UUID.class), rs.getInt("quantity")});
        for (Object[] row : released) {
            jdbc.update("UPDATE time_slot SET reserved = reserved - :q WHERE id = :id",
                    new MapSqlParameterSource("id", row[0]).addValue("q", row[1]));
        }
    }

    @Override
    public AvailabilityView availability(Resource resource, AvailabilityQuery query) {
        Instant from = query.from() != null ? query.from() : clock.instant();
        Instant to = query.to() != null ? query.to() : from.plus(DEFAULT_WINDOW);
        int needed = Math.max(1, query.quantity());
        List<AvailabilityView.SlotAvailability> slots = jdbc.query("""
                SELECT id, starts_at, ends_at, capacity, capacity - reserved AS free FROM time_slot
                 WHERE resource_id = :r AND starts_at >= :from AND starts_at < :to
                 ORDER BY starts_at LIMIT 500
                """, new MapSqlParameterSource("r", resource.getId()).addValue("from", ts(from)).addValue("to", ts(to)),
                (rs, i) -> new AvailabilityView.SlotAvailability(rs.getObject("id", UUID.class),
                        SqlTime.instant(rs, "starts_at"), SqlTime.instant(rs, "ends_at"),
                        rs.getInt("capacity"), rs.getInt("free")));
        int totalFree = slots.stream().mapToInt(AvailabilityView.SlotAvailability::available).sum();
        boolean any = slots.stream().anyMatch(s -> s.available() >= needed);
        return new AvailabilityView(resource.getId(), model(), any, null, totalFree, List.of(), slots);
    }

    /** Counter row(s) are the most contended locks: take them last in multi-item transactions. */
    @Override
    public int lockOrderRank() {
        return 1;
    }
}
