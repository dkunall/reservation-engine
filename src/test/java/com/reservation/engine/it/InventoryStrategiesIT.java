package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import com.reservation.engine.support.AbstractPostgresIT;
import com.reservation.engine.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Behaviour + schema constraints of each inventory model against real PostgreSQL. */
class InventoryStrategiesIT extends AbstractPostgresIT {

    @Autowired
    InventoryStrategyRegistry strategies;
    @Autowired
    TransactionTemplate tx;

    // ---------------- CAPACITY ----------------

    @Test
    void capacityAllocatesReleasesAndReportsAvailability() throws Exception {
        UUID bus = createResource("""
                {"type":"BUS","name":"Bus 42","price":{"amount":"12.50","currency":"USD"},"inventory":{"capacity":5}}""");
        UUID id = holdOk("alice", Map.of("resourceId", bus, "quantity", 3));
        hold("bob", key(), Map.of("resourceId", bus, "quantity", 3)).andExpect(status().isConflict());
        holdOk("bob", Map.of("resourceId", bus, "quantity", 2));
        assertAvailable(bus, 0);
        cancel("alice", id, key()).andExpect(status().isOk());
        assertAvailable(bus, 3);
    }

    @Test
    void capacityReleaseIsIdempotent() throws Exception {
        UUID bus = createCapacityResource(5, "1.00");
        UUID id = holdOk("alice", Map.of("resourceId", bus, "quantity", 2));
        tx.executeWithoutResult(s -> {
            strategies.get("CAPACITY").release(id, Instant.now());
            strategies.get("CAPACITY").release(id, Instant.now());
        });
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", bus)).isZero();
    }

    @Test
    void capacityCheckConstraintPreventsOversellingAtDbLevel() throws Exception {
        UUID bus = createCapacityResource(2, "1.00");
        assertThatThrownBy(() -> jdbc.update("UPDATE capacity_inventory SET reserved = 3 WHERE resource_id = ?", bus))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE capacity_inventory SET reserved = -1 WHERE resource_id = ?", bus))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------- EXCLUSIVE_UNIT ----------------

    @Test
    void exclusiveUnitExplicitAndAutoAssignment() throws Exception {
        UUID flight = createUnitResource("FLIGHT", "1A", "1B", "1C");
        UUID first = holdOk("alice", Map.of("resourceId", flight, "unitCodes", new String[]{"1A"}));
        hold("bob", key(), Map.of("resourceId", flight, "unitCodes", new String[]{"1A"}))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));
        JsonNode auto = body(hold("bob", key(), Map.of("resourceId", flight, "quantity", 2)).andExpect(status().isCreated()));
        assertThat(auto.get("unitCodes")).extracting(JsonNode::asText).containsExactlyInAnyOrder("1B", "1C");
        hold("carol", key(), Map.of("resourceId", flight)).andExpect(status().isConflict());
        hold("carol", key(), Map.of("resourceId", flight, "unitCodes", new String[]{"9Z"}))
                .andExpect(status().isUnprocessableEntity());
        cancel("alice", first, key()).andExpect(status().isOk());
        holdOk("carol", Map.of("resourceId", flight, "unitCodes", new String[]{"1A"}));
        assertThat(count("SELECT count(*) FROM unit_allocation WHERE status = 'ACTIVE'")).isEqualTo(3);
    }

    @Test
    void exclusiveUnitPartialFailureRollsBackWholeHold() throws Exception {
        UUID seats = createUnitResource("EVENT_SEAT", "A1", "A2");
        holdOk("alice", Map.of("resourceId", seats, "unitCodes", new String[]{"A2"}));
        hold("bob", key(), Map.of("resourceId", seats, "unitCodes", new String[]{"A1", "A2"}))
                .andExpect(status().isConflict());
        // A1 must still be free and no reservation row may exist for bob
        assertThat(count("SELECT count(*) FROM reservation WHERE user_id = 'bob'")).isZero();
        assertAvailable(seats, 1);
        holdOk("bob", Map.of("resourceId", seats, "unitCodes", new String[]{"A1"}));
    }

    @Test
    void partialUniqueIndexAllowsOnlyOneActiveAllocationPerUnit() throws Exception {
        UUID flight = createUnitResource("TRAIN", "C1");
        UUID r1 = holdOk("alice", Map.of("resourceId", flight, "unitCodes", new String[]{"C1"}));
        UUID unit = jdbc.queryForObject("SELECT id FROM inventory_unit WHERE resource_id = ?", UUID.class, flight);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO unit_allocation (id, reservation_id, unit_id, status, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'ACTIVE', now())""", r1, unit))
                .isInstanceOf(DataIntegrityViolationException.class);
        // released rows don't count
        jdbc.update("""
                INSERT INTO unit_allocation (id, reservation_id, unit_id, status, created_at, released_at)
                VALUES (gen_random_uuid(), ?, ?, 'RELEASED', now(), now())""", r1, unit);
    }

    // ---------------- TIME_SLOT ----------------

    @Test
    void timeSlotBookingBySlotIdOrStart() throws Exception {
        Instant s1 = daysFromNow(2);
        Instant s2 = s1.plus(Duration.ofMinutes(30));
        UUID clinic = createResource("""
                {"type":"APPOINTMENT","name":"Dr. Who","price":{"amount":"60.00","currency":"GBP"},
                 "inventory":{"slots":[{"startsAt":"%s","endsAt":"%s","capacity":2},
                                       {"startsAt":"%s","endsAt":"%s","capacity":1}]}}"""
                .formatted(s1, s1.plus(Duration.ofMinutes(30)), s2, s2.plus(Duration.ofMinutes(30))));
        JsonNode av = body(mvc.perform(get("/api/v1/resources/{id}/availability", clinic)
                .header("Authorization", TestJwt.bearer("alice", "USER"))).andExpect(status().isOk()));
        assertThat(av.get("slots")).hasSize(2);
        String slot1 = av.get("slots").get(0).get("slotId").asText();

        JsonNode r = body(hold("alice", key(), Map.of("resourceId", clinic, "slotId", slot1, "quantity", 2))
                .andExpect(status().isCreated()));
        assertThat(Instant.parse(r.get("startsAt").asText())).isEqualTo(s1);
        assertThat(r.get("total").get("amount").decimalValue()).isEqualByComparingTo("120.00");
        hold("bob", key(), Map.of("resourceId", clinic, "slotId", slot1)).andExpect(status().isConflict());
        holdOk("bob", Map.of("resourceId", clinic, "startsAt", s2.toString()));
        hold("carol", key(), Map.of("resourceId", clinic, "startsAt", s2.toString())).andExpect(status().isConflict());
        hold("carol", key(), Map.of("resourceId", clinic, "slotId", UUID.randomUUID())).andExpect(status().isUnprocessableEntity());
        hold("carol", key(), Map.of("resourceId", clinic)).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void pastSlotsCannotBeBooked() throws Exception {
        Instant past = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MINUTES);
        UUID clinic = createResource("""
                {"type":"APPOINTMENT","name":"Past","price":{"amount":"1","currency":"USD"},
                 "inventory":{"slots":[{"startsAt":"%s","endsAt":"%s","capacity":5}]}}"""
                .formatted(past, past.plus(Duration.ofMinutes(30))));
        hold("alice", key(), Map.of("resourceId", clinic, "startsAt", past.toString())).andExpect(status().isConflict());
    }

    @Test
    void timeSlotCheckConstraint() throws Exception {
        Instant s = daysFromNow(1);
        UUID clinic = createResource("""
                {"type":"APPOINTMENT","name":"C","price":{"amount":"1","currency":"USD"},
                 "inventory":{"slots":[{"startsAt":"%s","endsAt":"%s","capacity":1}]}}""".formatted(s, s.plusSeconds(900)));
        assertThatThrownBy(() -> jdbc.update("UPDATE time_slot SET reserved = 2 WHERE resource_id = ?", clinic))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------- TIME_RANGE ----------------

    @Test
    void timeRangeOverlapRulesAndAutoAssignment() throws Exception {
        UUID hotel = createUnitResource("HOTEL_ROOM", "101", "102");
        Instant d10 = daysFromNow(10), d11 = daysFromNow(11), d12 = daysFromNow(12), d13 = daysFromNow(13), d14 = daysFromNow(14);

        JsonNode r = body(hold("alice", key(), Map.of("resourceId", hotel, "unitCodes", new String[]{"101"},
                "startsAt", d10.toString(), "endsAt", d12.toString())).andExpect(status().isCreated()));
        assertThat(r.get("total").get("amount").decimalValue()).isEqualByComparingTo("200.00"); // 2 nights x 100
        UUID aliceId = UUID.fromString(r.get("id").asText());

        hold("bob", key(), Map.of("resourceId", hotel, "unitCodes", new String[]{"101"},
                "startsAt", d11.toString(), "endsAt", d13.toString())).andExpect(status().isConflict());
        // half-open ranges: checkout day == next check-in day is fine
        holdOk("bob", Map.of("resourceId", hotel, "unitCodes", new String[]{"101"},
                "startsAt", d12.toString(), "endsAt", d14.toString()));
        JsonNode any = body(hold("carol", key(), Map.of("resourceId", hotel,
                "startsAt", d11.toString(), "endsAt", d12.toString())).andExpect(status().isCreated()));
        assertThat(any.get("unitCodes").get(0).asText()).isEqualTo("102");
        hold("dave", key(), Map.of("resourceId", hotel, "startsAt", d11.toString(), "endsAt", d12.toString()))
                .andExpect(status().isConflict());

        JsonNode av = body(mvc.perform(get("/api/v1/resources/{id}/availability", hotel)
                        .param("from", d11.toString()).param("to", d12.toString())
                        .header("Authorization", TestJwt.bearer("alice", "USER"))).andExpect(status().isOk()));
        assertThat(av.get("availableQuantity").asInt()).isZero();

        cancel("alice", aliceId, key()).andExpect(status().isOk());
        holdOk("dave", Map.of("resourceId", hotel, "startsAt", d11.toString(), "endsAt", d12.toString()));
        // availability without a range is a 422
        mvc.perform(get("/api/v1/resources/{id}/availability", hotel).header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void exclusionConstraintRejectsOverlapOnlyForActiveRows() throws Exception {
        UUID hotel = createUnitResource("HOTEL_ROOM", "201");
        Instant a = daysFromNow(20), b = daysFromNow(22);
        UUID res = holdOk("alice", Map.of("resourceId", hotel, "startsAt", a.toString(), "endsAt", b.toString()));
        UUID unit = jdbc.queryForObject("SELECT id FROM inventory_unit WHERE resource_id = ?", UUID.class, hotel);
        String insert = """
                INSERT INTO range_allocation (id, reservation_id, unit_id, period, status, created_at)
                VALUES (gen_random_uuid(), ?, ?, tstzrange(now() + interval '21 days', now() + interval '23 days', '[)'), ?, now())""";
        assertThatThrownBy(() -> jdbc.update(insert, res, unit, "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, res, unit, "RELEASED");
    }

    private void assertAvailable(UUID resource, int expected) throws Exception {
        mvc.perform(get("/api/v1/resources/{id}/availability", resource).header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableQuantity").value(expected));
    }
}
