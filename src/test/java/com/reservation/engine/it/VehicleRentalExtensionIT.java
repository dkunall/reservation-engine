package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The VEHICLE (code extension) and PARKING_SPOT (config-only) types run through the unchanged core workflow. */
class VehicleRentalExtensionIT extends AbstractPostgresIT {

    private UUID createVehicle() throws Exception {
        return createResource("""
                {"type":"VEHICLE","name":"VW Golf","price":{"amount":"12.00","currency":"EUR"},
                 "attributes":{"make":"VW","model":"Golf","seats":5},"inventory":{"unitCodes":["B-XY-123"]}}""");
    }

    @Test
    void vehicleTypeValidatesAttributes() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/resources")
                        .header("Authorization", com.reservation.engine.support.TestJwt.bearer(ADMIN, "ADMIN"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"VEHICLE\",\"name\":\"x\",\"price\":{\"amount\":\"1\",\"currency\":\"EUR\"},\"attributes\":{\"make\":\"VW\"}}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void vehicleRentalHourlyPricingOverlapAndTtl() throws Exception {
        UUID car = createVehicle();
        Instant pickup = daysFromNow(3);
        JsonNode r = body(hold("alice", key(), Map.of("resourceId", car, "startsAt", pickup.toString(),
                "endsAt", pickup.plus(Duration.ofMinutes(150)).toString())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.inventoryModel").value("TIME_RANGE"))
                .andExpect(jsonPath("$.unitCodes[0]").value("B-XY-123")));
        assertThat(r.get("total").get("amount").decimalValue()).isEqualByComparingTo("36.00"); // 3 started hours x 12
        Instant created = Instant.parse(r.get("createdAt").asText());
        Instant expires = Instant.parse(r.get("holdExpiresAt").asText());
        assertThat(Duration.between(created, expires)).isEqualTo(Duration.ofMinutes(20));

        hold("bob", key(), Map.of("resourceId", car, "startsAt", pickup.plus(Duration.ofHours(1)).toString(),
                "endsAt", pickup.plus(Duration.ofHours(5)).toString())).andExpect(status().isConflict());
        hold("bob", key(), Map.of("resourceId", car, "startsAt", pickup.plus(Duration.ofDays(1)).toString(),
                "endsAt", pickup.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(30)).toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Minimum")));
    }

    @Test
    void vehicleCancellationPolicyGivesHalfRefundInsideTwentyFourHours() throws Exception {
        UUID car = createVehicle();
        Instant pickup = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofHours(3));
        UUID id = holdOk("alice", Map.of("resourceId", car, "startsAt", pickup.toString(),
                "endsAt", pickup.plus(Duration.ofHours(4)).toString()));
        confirm("alice", id, key(), null).andExpect(status().isOk());
        cancel("alice", id, key()).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_transaction WHERE reservation_id = ? AND type='REFUND'",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("24.00"); // 50% of 48.00
    }

    @Test
    void configOnlyParkingSpotType() throws Exception {
        UUID parking = createResource("""
                {"type":"PARKING_SPOT","name":"Garage L2","price":{"amount":"3.00","currency":"USD"},
                 "inventory":{"unitCodes":["P1"]}}""");
        Instant s = daysFromNow(1);
        JsonNode r = body(hold("alice", key(), Map.of("resourceId", parking, "startsAt", s.toString(),
                "endsAt", s.plus(Duration.ofHours(2)).toString())).andExpect(status().isCreated()));
        assertThat(r.get("total").get("amount").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(Duration.between(Instant.parse(r.get("createdAt").asText()), Instant.parse(r.get("holdExpiresAt").asText())))
                .isEqualTo(Duration.ofMinutes(5));
    }
}
