package com.reservation.engine.inventory.unit;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.reservation.engine.persistence.SqlTime.ts;

/**
 * EXCLUSIVE_UNIT model (specific seat / vehicle / ticket): a unit has at most one ACTIVE allocation,
 * enforced by a partial unique index. Explicit selections row-lock the requested units (ordered by id to
 * avoid deadlocks) before checking; "any N units" picks free units with FOR UPDATE SKIP LOCKED and inserts
 * each inside a savepoint so a lost race just moves on to the next candidate.
 */
@Component
public class ExclusiveUnitInventoryStrategy implements InventoryStrategy {

    private static final int MAX_ROUNDS = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryUnitDao units;
    private final SavepointTemplate savepoints;
    private final Clock clock;

    public ExclusiveUnitInventoryStrategy(NamedParameterJdbcTemplate jdbc, InventoryUnitDao units,
                                          SavepointTemplate savepoints, Clock clock) {
        this.jdbc = jdbc;
        this.units = units;
        this.savepoints = savepoints;
        this.clock = clock;
    }

    @Override
    public String model() {
        return InventoryModels.EXCLUSIVE_UNIT;
    }

    @Override
    public void addInventory(Resource resource, InventorySetup setup) {
        units.addUnits(resource.getId(), setup.unitCodes(), clock.instant());
    }

    @Override
    public void validateRequest(Resource resource, AllocationRequest request) {
        InventoryUnitDao.validateUnitSelection(request.quantity(), request.unitCodes());
        if (request.slotId() != null || request.startsAt() != null || request.endsAt() != null) {
            throw new InvalidRequestException("EXCLUSIVE_UNIT resources do not accept slotId/startsAt/endsAt");
        }
    }

    @Override
    public long billableUnits(AllocationRequest request, ResourceTypeDefinition type) {
        return request.quantity();
    }

    @Override
    public AllocationResult allocate(Resource resource, UUID reservationId, AllocationRequest request) {
        List<String> allocated = request.hasUnitCodes()
                ? allocateExplicit(resource, reservationId, request.unitCodes())
                : allocateAny(resource, reservationId, request.quantity());
        return new AllocationResult(allocated.size(), allocated, null, null, null);
    }

    private List<String> allocateExplicit(Resource resource, UUID reservationId, List<String> codes) {
        List<InventoryUnitDao.Unit> locked = units.resolve(resource.getId(), codes, true);
        List<UUID> ids = locked.stream().map(InventoryUnitDao.Unit::id).toList();
        List<UUID> taken = jdbc.queryForList(
                "SELECT unit_id FROM unit_allocation WHERE unit_id IN (:ids) AND status = 'ACTIVE'",
                new MapSqlParameterSource("ids", ids), UUID.class);
        if (!taken.isEmpty()) {
            Set<UUID> takenSet = new HashSet<>(taken);
            String takenCodes = locked.stream().filter(u -> takenSet.contains(u.id()))
                    .map(InventoryUnitDao.Unit::code).sorted().collect(Collectors.joining(","));
            throw new InventoryUnavailableException("Units already reserved: " + takenCodes);
        }
        try {
            for (InventoryUnitDao.Unit u : locked) {
                insertAllocation(reservationId, u.id());
            }
        } catch (DataIntegrityViolationException e) {
            // backstop: partial unique index caught a race the lock did not (should not happen)
            throw new InventoryUnavailableException("Units already reserved: " + codes);
        }
        return codes;
    }

    private List<String> allocateAny(Resource resource, UUID reservationId, int quantity) {
        List<String> got = new ArrayList<>();
        Set<UUID> tried = new HashSet<>();
        for (int round = 0; round < MAX_ROUNDS && got.size() < quantity; round++) {
            List<InventoryUnitDao.Unit> candidates = jdbc.query("""
                    SELECT u.id, u.unit_code FROM inventory_unit u
                     WHERE u.resource_id = :r AND u.active
                       AND NOT EXISTS (SELECT 1 FROM unit_allocation a WHERE a.unit_id = u.id AND a.status = 'ACTIVE')
                     ORDER BY u.unit_code
                     LIMIT :n
                     FOR UPDATE OF u SKIP LOCKED
                    """, new MapSqlParameterSource("r", resource.getId()).addValue("n", quantity - got.size() + tried.size()),
                    (rs, i) -> new InventoryUnitDao.Unit(rs.getObject("id", UUID.class), rs.getString("unit_code")));
            boolean progressed = false;
            for (InventoryUnitDao.Unit u : candidates) {
                if (got.size() == quantity) {
                    break;
                }
                if (!tried.add(u.id())) {
                    continue;
                }
                progressed = true;
                savepoints.tryWithSavepoint(() -> insertAllocation(reservationId, u.id()))
                        .ifPresent(x -> got.add(u.code()));
            }
            if (!progressed) {
                break;
            }
        }
        if (got.size() < quantity) {
            throw new InventoryUnavailableException("Only " + got.size() + " of " + quantity
                    + " units available for resource " + resource.getId());
        }
        return got;
    }

    private int insertAllocation(UUID reservationId, UUID unitId) {
        return jdbc.update("""
                INSERT INTO unit_allocation (id, reservation_id, unit_id, status, created_at)
                VALUES (:id, :res, :u, 'ACTIVE', :now)
                """, new MapSqlParameterSource("id", UUID.randomUUID()).addValue("res", reservationId)
                .addValue("u", unitId).addValue("now", ts(clock.instant())));
    }

    @Override
    public void release(UUID reservationId, Instant now) {
        jdbc.update("""
                UPDATE unit_allocation SET status = 'RELEASED', released_at = :now
                 WHERE reservation_id = :res AND status = 'ACTIVE'
                """, new MapSqlParameterSource("res", reservationId).addValue("now", ts(now)));
    }

    @Override
    public AvailabilityView availability(Resource resource, AvailabilityQuery query) {
        List<AvailabilityView.UnitAvailability> list = jdbc.query("""
                SELECT u.unit_code,
                       NOT EXISTS (SELECT 1 FROM unit_allocation a WHERE a.unit_id = u.id AND a.status = 'ACTIVE') AS free
                  FROM inventory_unit u WHERE u.resource_id = :r AND u.active ORDER BY u.unit_code
                """, new MapSqlParameterSource("r", resource.getId()),
                (rs, i) -> new AvailabilityView.UnitAvailability(rs.getString("unit_code"), rs.getBoolean("free")));
        int free = (int) list.stream().filter(AvailabilityView.UnitAvailability::available).count();
        return new AvailabilityView(resource.getId(), model(), free >= Math.max(1, query.quantity()),
                list.size(), free, list, List.of());
    }
}
