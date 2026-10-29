package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.support.AbstractPostgresIT;
import com.reservation.engine.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReservationApiIT extends AbstractPostgresIT {

    @Test
    void fullLifecycleHoldConfirmCancelWithAuditAndOutbox() throws Exception {
        UUID resource = createCapacityResource(10, "49.99");

        var held = hold("alice", key(), Map.of("resourceId", resource, "quantity", 2))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.total.amount").value(99.98))
                .andExpect(jsonPath("$.total.currency").value("USD"))
                .andExpect(jsonPath("$.holdExpiresAt", notNullValue()));
        UUID id = UUID.fromString(body(held).get("id").asText());
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", resource)).isEqualTo(2);

        getReservation("alice", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceName").value("Concert GA"));

        confirm("alice", id, key(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt", notNullValue()));
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE reservation_id = ? AND type='CHARGE' AND status='SUCCEEDED'", id))
                .isEqualTo(1);

        cancel("alice", id, key()).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", resource)).isZero();
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE reservation_id = ? AND type='REFUND' AND status='PENDING'", id))
                .isEqualTo(1);

        // audit: null->HELD, HELD->PAYMENT_PENDING, ->CONFIRMED, ->CANCELLED
        assertThat(jdbc.queryForList("SELECT to_status FROM reservation_audit WHERE reservation_id = ? ORDER BY id", String.class, id))
                .containsExactly("HELD", "PAYMENT_PENDING", "CONFIRMED", "CANCELLED");
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at, event_type", String.class, id))
                .contains("ReservationHeld", "ReservationPaymentPending", "ReservationConfirmed", "ReservationCancelled");
    }

    @Test
    void createReturnsLocationHeader() throws Exception {
        UUID resource = createCapacityResource(1, "5.00");
        var r = hold("alice", key(), Map.of("resourceId", resource)).andExpect(status().isCreated());
        String id = body(r).get("id").asText();
        assertThat(r.andReturn().getResponse().getHeader("Location")).isEqualTo("/api/v1/reservations/" + id);
    }

    @Test
    void soldOutReturns409Problem() throws Exception {
        UUID resource = createCapacityResource(1, "5.00");
        holdOk("alice", Map.of("resourceId", resource, "quantity", 1));
        hold("bob", key(), Map.of("resourceId", resource, "quantity", 1))
                .andExpect(status().isConflict())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void listOwnReservationsIsPaginatedAndScopedToCaller() throws Exception {
        UUID resource = createCapacityResource(50, "1.00");
        for (int i = 0; i < 3; i++) {
            holdOk("alice", Map.of("resourceId", resource));
        }
        holdOk("bob", Map.of("resourceId", resource));
        mvc.perform(get("/api/v1/reservations?page=0&size=2").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/reservations?status=CONFIRMED").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/reservations?size=500").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ownershipIsEnforcedWith403() throws Exception {
        UUID resource = createCapacityResource(5, "1.00");
        UUID id = holdOk("alice", Map.of("resourceId", resource));
        getReservation("mallory", id).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        confirm("mallory", id, key(), null).andExpect(status().isForbidden());
        cancel("mallory", id, key()).andExpect(status().isForbidden());
        assertThat(statusOf(id)).isEqualTo("HELD");
        // admins may read any reservation
        mvc.perform(get("/api/v1/reservations/{id}", id).header("Authorization", TestJwt.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void unknownReservationAndResourceReturn404() throws Exception {
        getReservation("alice", UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        hold("alice", key(), Map.of("resourceId", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/resources/{id}", UUID.randomUUID()).header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void authenticationAndAdminRoleAreRequired() throws Exception {
        mvc.perform(get("/api/v1/reservations")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/reservations").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
        String forged = TestJwt.token("another-secret-that-is-long-enough-0123456789", "alice", Instant.now().plusSeconds(60), "ADMIN");
        mvc.perform(get("/api/v1/reservations").header("Authorization", "Bearer " + forged)).andExpect(status().isUnauthorized());
        String expired = TestJwt.token(TestJwt.SECRET, "alice", Instant.now().minusSeconds(3600), "USER");
        mvc.perform(get("/api/v1/reservations").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/resources").header("Authorization", TestJwt.bearer("alice", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUS\",\"name\":\"x\",\"price\":{\"amount\":\"1\",\"currency\":\"USD\"}}"))
                .andExpect(status().isForbidden());
        UUID resource = createCapacityResource(1, "1.00");
        mvc.perform(post("/api/v1/resources/{id}/inventory", resource).header("Authorization", TestJwt.bearer("alice", "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validationErrorsReturn400WithFieldErrors() throws Exception {
        UUID resource = createCapacityResource(1, "1.00");
        hold("alice", key(), "{\"quantity\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems("resourceId", "quantity")));
        hold("alice", key(), "{not json").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/reservations").header("Authorization", TestJwt.bearer("alice", "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("resourceId", resource))))
                .andExpect(status().isBadRequest()); // missing Idempotency-Key
        hold("alice", "bad key!", Map.of("resourceId", resource))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        mvc.perform(post("/api/v1/resources").header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bus\",\"name\":\"\",\"price\":{\"amount\":\"-1\",\"currency\":\"usd\"}}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void semanticErrorsReturn422() throws Exception {
        UUID capacity = createCapacityResource(5, "1.00");
        hold("alice", key(), Map.of("resourceId", capacity, "unitCodes", new String[]{"A1"}))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        UUID hotel = createUnitResource("HOTEL_ROOM", "101");
        hold("alice", key(), Map.of("resourceId", hotel))
                .andExpect(status().isUnprocessableEntity()); // TIME_RANGE without dates
        mvc.perform(post("/api/v1/resources").header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SPACESHIP\",\"name\":\"x\",\"price\":{\"amount\":\"1\",\"currency\":\"USD\"}}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/resources").header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BUS\",\"name\":\"x\",\"price\":{\"amount\":\"1\",\"currency\":\"ABC\"}}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void invalidTransitionsReturn409() throws Exception {
        UUID resource = createCapacityResource(5, "1.00");
        UUID id = holdOk("alice", Map.of("resourceId", resource));
        cancel("alice", id, key()).andExpect(status().isOk());
        confirm("alice", id, key(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        cancel("alice", id, key()).andExpect(status().isConflict());
    }

    @Test
    void confirmingAnExpiredHoldReturns409AndReleasesInventory() throws Exception {
        UUID resource = createCapacityResource(1, "1.00");
        UUID id = holdOk("alice", Map.of("resourceId", resource));
        forceHoldExpiry(id);
        confirm("alice", id, key(), null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", resource)).isZero();
    }

    @Test
    void declinedPaymentReturns402AndKeepsHold() throws Exception {
        UUID resource = createCapacityResource(1, "10.00");
        UUID id = holdOk("alice", Map.of("resourceId", resource));
        confirm("alice", id, key(), "tok_decline").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("PAYMENT_DECLINED"));
        assertThat(statusOf(id)).isEqualTo("HELD");
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", resource)).isEqualTo(1);
        // retry with a working card succeeds
        confirm("alice", id, key(), "tok_ok").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void unknownPaymentOutcomeReturns202ThenReconciles() throws Exception {
        UUID resource = createCapacityResource(1, "10.00");
        UUID id = holdOk("alice", Map.of("resourceId", resource));
        confirm("alice", id, key(), "tok_unknown").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        assertThat(statusOf(id)).isEqualTo("PAYMENT_PENDING");
        // a second confirm while payment is in flight is a conflict, not a double charge
        confirm("alice", id, key(), null).andExpect(status().isConflict());
    }

    @Test
    void resourceAdminEndpointsAndAvailability() throws Exception {
        UUID flight = createUnitResource("FLIGHT", "1A", "1B", "1C");
        mvc.perform(post("/api/v1/resources/{id}/inventory", flight).header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"unitCodes\":[\"2A\"]}"))
                .andExpect(status().isOk());
        holdOk("alice", Map.of("resourceId", flight, "unitCodes", new String[]{"1B"}));
        JsonNode av = body(mvc.perform(get("/api/v1/resources/{id}/availability", flight)
                .header("Authorization", TestJwt.bearer("alice", "USER"))).andExpect(status().isOk()));
        assertThat(av.get("capacity").asInt()).isEqualTo(4);
        assertThat(av.get("availableQuantity").asInt()).isEqualTo(3);

        mvc.perform(get("/api/v1/resources?type=FLIGHT&page=0&size=10").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].inventoryModel").value("EXCLUSIVE_UNIT"));
        mvc.perform(get("/api/v1/resource-types").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(org.hamcrest.Matchers.hasItems(
                        "FLIGHT", "HOTEL_ROOM", "BUS", "TRAIN", "EVENT_GA", "EVENT_SEAT", "APPOINTMENT", "VEHICLE", "PARKING_SPOT")));
    }

    @Test
    void correlationIdIsEchoedAndHealthIsPublic() throws Exception {
        mvc.perform(get("/api/v1/reservations").header("X-Correlation-Id", "abc-123")
                        .header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk()).andExpect(header().string("X-Correlation-Id", "abc-123"));
        mvc.perform(get("/api/v1/reservations").header("X-Correlation-Id", "bad id with spaces"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.not("bad id with spaces")));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", TestJwt.bearer("alice", "USER"))).andExpect(status().isForbidden());
        UUID resource = createCapacityResource(1, "1.00");
        holdOk("alice", Map.of("resourceId", resource));
        mvc.perform(get("/actuator/metrics/reservation.holds").header("Authorization", TestJwt.bearer(ADMIN, "ADMIN")))
                .andExpect(status().isOk());
    }
}
