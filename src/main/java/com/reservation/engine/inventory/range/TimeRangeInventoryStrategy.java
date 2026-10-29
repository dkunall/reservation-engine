package com.reservation.engine.inventory.range;

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
import com.reservation.engine.inventory.unit.InventoryUnitDao;
import com.reservation.engine.persistence.SavepointTemplate;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.reservation.engine.persistence.SqlTime.ts;

/**
 * TIME_RANGE model (hotel room nights, vehicle rental): a btree_gist EXCLUDE constraint on
 * (unit_id =, period &&) WHERE status = 'ACTIVE' makes overlapping active allocations impossible.
 * <p>
 * Writers additionally take a short row lock on the unit (in unit-id order) before inserting. Without it,
 * two transactions inserting overlapping periods for the same unit can both place their index entries and
 * then wait on each other inside the exclusion check (PostgreSQL reports a deadlock; observed in
 * ConcurrencyIT). With the lock, writers queue per unit and the loser gets a clean conflict.
 * The exclusion constraint stays the correctness guarantee; the lock only avoids deadlocks.
 * "Any unit" requests re-check each candidate after locking it and insert inside a savepoint.
 */
@Component
public class TimeRangeInventoryStrategy implements InventoryStrategy {

    private static final Duration MAX_RANGE = Duration.ofDays(366);

    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryUnitDao units;
    private final SavepointTemplate savepoints;
    private final Clock clock;

    public TimeRangeInventoryStrategy(NamedParameterJdbcTemplate jdbc, InventoryUnitDao units,
                                      SavepointTemplate savepoints, Clock clock) {
        this.jdbc = jdbc;
        this.units = units;
        this.savepoints = savepoints;
        this.clock = clock;
    }

    @Override
    public String model() {
        return InventoryModels.TIME_RANGE;
    }

    @Override
    public void addInventory(Resource resource, InventorySetup setup) {
        units.addUnits(resource.getId(), setup.unitCodes(), clock.instant());
    }

    @Override
    public void validateRequest(Resource resource, AllocationRequest request) {
        InventoryUnitDao.validateUnitSelection(request.quantity(), request.unitCodes());
        if (request.slotId() != null) {
            throw new InvalidRequestException("TIME_RANGE resources do not accept slotId");
        }
        if (request.startsAt() == null || request.endsAt() == null) {
            throw new InvalidRequestException("TIME_RANGE resources require startsAt and endsAt");
        }
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new InvalidRequestException("endsAt must be after startsAt");
        }
        if (request.startsAt().isBefore(clock.instant())) {
            throw new InvalidRequestException("startsAt must be in the future");
        }
        if (Duration.between(request.startsAt(), request.endsAt()).compareTo(MAX_RANGE) > 0) {
            throw new InvalidRequestException("range longer than " + MAX_RANGE.toDays() + " days");
        }
    }

    @Override
    public long billableUnits(AllocationRequest request, ResourceTypeDefinition type) {
        long periodSeconds = type.billingPeriod().toSeconds();
        long seconds = Duration.between(request.startsAt(), request.endsAt()).toSeconds();
        long periods = Math.max(1, (seconds + periodSeconds - 1) / periodSeconds);
        return periods * request.quantity();
    }

    @Override
    public AllocationResult allocate(Resource resource, UUID reservationId, AllocationRequest request) {
        List<String> allocated = request.hasUnitCodes()
                ? allocateExplicit(resource, reservationId, request)
                : allocateAny(resource, reservationId, request);
        return new AllocationResult(allocated.size(), allocated, null, null, null);
    }

    private List<String> allocateExplicit(Resource resource, UUID reservationId, AllocationRequest request) {
        List<InventoryUnitDao.Unit> resolved = units.resolve(resource.getId(), request.unitCodes(), true);
        try {
            for (InventoryUnitDao.Unit u : resolved) {
                insert(reservationId, u.id(), request);
            }
        } catch (DataIntegrityViolationException e) {
            throw new InventoryUnavailableException("Requested units are not available for "
                    + request.startsAt() + " - " + request.endsAt());
        }
        return request.unitCodes();
    }

    /**
     * Any-unit allocation in two passes.
     * <ol>
     *   <li><b>Non-blocking</b>: lock free candidates with {@code FOR UPDATE SKIP LOCKED}, re-check with a fresh
     *       snapshot, insert inside a savepoint. Concurrent requests spread over different units instead of
     *       queueing on the lowest-id free unit.</li>
     *   <li><b>Blocking</b> (only if pass 1 could not get enough units, e.g. every free unit was momentarily locked):
     *       the original ordered, waiting pass, so a request is never rejected while capacity exists.</li>
     * </ol>
     * Pass 1 runs inside a savepoint that is rolled back when it falls short: this drops its partial inserts AND the
     * row locks it took (PostgreSQL keeps a lock on a row that fails the re-check), so the waiting pass starts
     * without stray locks and waits only in unit-id order (deadlock-free).
     */
    private List<String> allocateAny(Resource resource, UUID reservationId, AllocationRequest request) {
        MapSqlParameterSource p = new MapSqlParameterSource("r", resource.getId())
                .addValue("s", ts(request.startsAt())).addValue("e", ts(request.endsAt()));
        List<InventoryUnitDao.Unit> candidates = jdbc.query("""
                SELECT u.id, u.unit_code FROM inventory_unit u
                 WHERE u.resource_id = :r AND u.active
                   AND NOT EXISTS (SELECT 1 FROM range_allocation a
                                    WHERE a.unit_id = u.id AND a.status = 'ACTIVE'
                                      AND a.period && tstzrange(:s, :e, '[)'))
                 ORDER BY u.id
                """, p, (rs, i) -> new InventoryUnitDao.Unit(rs.getObject("id", UUID.class), rs.getString("unit_code")));
        if (candidates.size() < request.quantity()) {
            throw unavailable(request);
        }
        Optional<List<String>> fast = savepoints.keepIfPresent(() -> {
            List<String> got = takeUnits(candidates, reservationId, request, p, true);
            return got.size() == request.quantity() ? Optional.of(got) : Optional.empty();
        });
        if (fast.isPresent()) {
            return fast.get();
        }
        List<String> got = takeUnits(candidates, reservationId, request, p, false);
        if (got.size() < request.quantity()) {
            throw unavailable(request);
        }
        return got;
    }

    private List<String> takeUnits(List<InventoryUnitDao.Unit> candidates, UUID reservationId, AllocationRequest request,
                                   MapSqlParameterSource p, boolean skipLocked) {
        List<String> got = new ArrayList<>();
        for (InventoryUnitDao.Unit u : candidates) {
            if (got.size() == request.quantity()) {
                break;
            }
            // lock the unit (skip it / queue behind concurrent writers), then re-check with a fresh snapshot
            List<Boolean> free = jdbc.queryForList("""
                    SELECT NOT EXISTS (SELECT 1 FROM range_allocation a
                                        WHERE a.unit_id = u.id AND a.status = 'ACTIVE'
                                          AND a.period && tstzrange(:s, :e, '[)'))
                      FROM inventory_unit u WHERE u.id = :u FOR UPDATE OF u
                    """ + (skipLocked ? " SKIP LOCKED" : ""),
                    new MapSqlParameterSource(p.getValues()).addValue("u", u.id()), Boolean.class);
            if (!free.isEmpty() && Boolean.TRUE.equals(free.get(0))) {
                savepoints.tryWithSavepoint(() -> insert(reservationId, u.id(), request)).ifPresent(x -> got.add(u.code()));
            }
        }
        return got;
    }

    private static InventoryUnavailableException unavailable(AllocationRequest request) {
        return new InventoryUnavailableException("Not enough units available for "
                + request.startsAt() + " - " + request.endsAt());
    }

    private int insert(UUID reservationId, UUID unitId, AllocationRequest request) {
        return jdbc.update("""
                INSERT INTO range_allocation (id, reservation_id, unit_id, period, status, created_at)
                VALUES (:id, :res, :u, tstzrange(:s, :e, '[)'), 'ACTIVE', :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("res", reservationId)
                .addValue("u", unitId).addValue("s", ts(request.startsAt())).addValue("e", ts(request.endsAt()))
                .addValue("now", ts(clock.instant())));
    }

    @Override
    public void release(UUID reservationId, Instant now) {
        jdbc.update("""
                UPDATE range_allocation SET status = 'RELEASED', released_at = :now
                 WHERE reservation_id = :res AND status = 'ACTIVE'
                """, new MapSqlParameterSource("res", reservationId).addValue("now", ts(now)));
    }

    @Override
    public AvailabilityView availability(Resource resource, AvailabilityQuery query) {
        if (query.from() == null || query.to() == null || !query.to().isAfter(query.from())) {
            throw new InvalidRequestException("TIME_RANGE availability requires from < to");
        }
        List<AvailabilityView.UnitAvailability> list = jdbc.query("""
                SELECT u.unit_code,
                       NOT EXISTS (SELECT 1 FROM range_allocation a
                                    WHERE a.unit_id = u.id AND a.status = 'ACTIVE'
                                      AND a.period && tstzrange(:s, :e, '[)')) AS free
                  FROM inventory_unit u WHERE u.resource_id = :r AND u.active ORDER BY u.unit_code
                """, new MapSqlParameterSource("r", resource.getId())
                        .addValue("s", ts(query.from())).addValue("e", ts(query.to())),
                (rs, i) -> new AvailabilityView.UnitAvailability(rs.getString("unit_code"), rs.getBoolean("free")));
        int free = (int) list.stream().filter(AvailabilityView.UnitAvailability::available).count();
        return new AvailabilityView(resource.getId(), model(), free >= Math.max(1, query.quantity()),
                list.size(), free, list, List.of());
    }
}
