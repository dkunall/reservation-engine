package com.reservation.engine.inventory.unit;

import com.reservation.engine.domain.exception.InvalidRequestException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.reservation.engine.persistence.SqlTime.ts;

/** Access to physical units (seats, rooms, vehicles) shared by unit-based inventory models. */
@Component
public class InventoryUnitDao {

    public record Unit(UUID id, String code) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryUnitDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void addUnits(UUID resourceId, List<String> codes, Instant now) {
        if (codes.isEmpty()) {
            throw new InvalidRequestException("unit-based inventory requires non-empty 'unitCodes'");
        }
        for (String code : codes) {
            if (code == null || code.isBlank() || code.length() > 50) {
                throw new InvalidRequestException("unit codes must be 1..50 characters");
            }
        }
        if (new HashSet<>(codes).size() != codes.size()) {
            throw new InvalidRequestException("duplicate unit codes in request");
        }
        MapSqlParameterSource[] batch = codes.stream()
                .map(c -> new MapSqlParameterSource("id", UUID.randomUUID()).addValue("r", resourceId)
                        .addValue("c", c).addValue("now", ts(now)))
                .toArray(MapSqlParameterSource[]::new);
        // idempotent provisioning: existing codes are left untouched
        jdbc.batchUpdate("""
                INSERT INTO inventory_unit (id, resource_id, unit_code, active, created_at)
                VALUES (:id, :r, :c, true, :now) ON CONFLICT (resource_id, unit_code) DO NOTHING
                """, batch);
    }

    /** Resolves requested codes; fails with 422 if any is unknown. Optionally row-locks them in id order. */
    public List<Unit> resolve(UUID resourceId, Collection<String> codes, boolean lock) {
        String sql = "SELECT id, unit_code FROM inventory_unit WHERE resource_id = :r AND active AND unit_code IN (:codes) ORDER BY id"
                + (lock ? " FOR UPDATE" : "");
        List<Unit> units = jdbc.query(sql, new MapSqlParameterSource("r", resourceId).addValue("codes", codes),
                (rs, i) -> new Unit(rs.getObject("id", UUID.class), rs.getString("unit_code")));
        if (units.size() != new HashSet<>(codes).size()) {
            Set<String> missing = new HashSet<>(codes);
            units.forEach(u -> missing.remove(u.code()));
            throw new InvalidRequestException("Unknown unit codes: " + missing);
        }
        return units;
    }

    public List<Unit> listActive(UUID resourceId) {
        return jdbc.query("SELECT id, unit_code FROM inventory_unit WHERE resource_id = :r AND active ORDER BY unit_code",
                new MapSqlParameterSource("r", resourceId),
                (rs, i) -> new Unit(rs.getObject("id", UUID.class), rs.getString("unit_code")));
    }

    /** Shared request checks for unit-based models. */
    public static void validateUnitSelection(int quantity, List<String> unitCodes) {
        if (quantity < 1) {
            throw new InvalidRequestException("quantity must be >= 1");
        }
        if (!unitCodes.isEmpty()) {
            if (new HashSet<>(unitCodes).size() != unitCodes.size()) {
                throw new InvalidRequestException("duplicate unitCodes");
            }
            if (quantity != unitCodes.size()) {
                throw new InvalidRequestException("quantity must equal number of unitCodes when unitCodes are given");
            }
        }
    }
}
