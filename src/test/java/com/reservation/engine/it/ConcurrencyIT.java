package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.application.ConfirmationService;
import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.application.ExpirySweeper;
import com.reservation.engine.application.ReservationService;
import com.reservation.engine.application.ReservationView;
import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.exception.DomainException;
import com.reservation.engine.domain.exception.HoldExpiredException;
import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.support.AbstractPostgresIT;
import com.reservation.engine.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Races against real PostgreSQL. Each test asserts the exact number of winners plus DB invariants.
 */
class ConcurrencyIT extends AbstractPostgresIT {

    private static final int THREADS = 20;

    @Autowired
    ReservationService reservations;
    @Autowired
    ConfirmationService confirmations;
    @Autowired
    ExpirySweeper sweeper;

    private Object holdVia(String user, UUID resource, AllocationRequest req) {
        return reservations.hold(CurrentUser.user(user), new ReservationService.HoldCommand(resource, req));
    }

    private static long wins(List<Object> results) {
        return results.stream().filter(r -> r instanceof ReservationView).count();
    }

    private static void assertOnlyConflictsBesidesWins(List<Object> results) {
        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOfAny(ReservationView.class, InventoryUnavailableException.class));
    }

    @Test
    void capacityLastSeatsExactlyCapacityWinners() throws Exception {
        UUID ga = createCapacityResource(5, "10.00");
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, ga,
                new AllocationRequest(1, List.of(), null, null, null)));
        assertOnlyConflictsBesidesWins(results);
        assertThat(wins(results)).isEqualTo(5);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(5);
        assertThat(count("SELECT coalesce(sum(quantity),0) FROM capacity_allocation WHERE resource_id = ? AND status='ACTIVE'", ga)).isEqualTo(5);
        assertThat(count("SELECT count(*) FROM reservation WHERE resource_id = ?", ga)).isEqualTo(5);
    }

    @Test
    void capacityMixedQuantitiesNeverOversell() throws Exception {
        UUID ga = createCapacityResource(7, "1.00");
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, ga,
                new AllocationRequest(1 + (i % 3), List.of(), null, null, null)));
        assertOnlyConflictsBesidesWins(results);
        int reserved = count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga);
        int allocated = count("SELECT coalesce(sum(quantity),0) FROM capacity_allocation WHERE resource_id = ? AND status='ACTIVE'", ga);
        assertThat(reserved).isLessThanOrEqualTo(7).isEqualTo(allocated);
        assertThat(reserved).isGreaterThanOrEqualTo(5); // at least 7-2 seats must be sold given sizes 1..3
    }

    @Test
    void exclusiveSameUnitExactlyOneWinner() throws Exception {
        UUID flight = createUnitResource("FLIGHT", "12A", "12B");
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, flight,
                new AllocationRequest(1, List.of("12A"), null, null, null)));
        assertOnlyConflictsBesidesWins(results);
        assertThat(wins(results)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM unit_allocation a JOIN inventory_unit u ON u.id = a.unit_id
                 WHERE u.unit_code = '12A' AND u.resource_id = ? AND a.status = 'ACTIVE'""", flight)).isEqualTo(1);
    }

    @Test
    void exclusiveAnyUnitExactlyUnitCountWinners() throws Exception {
        UUID seats = createUnitResource("EVENT_SEAT", "S1", "S2", "S3");
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, seats,
                new AllocationRequest(1, List.of(), null, null, null)));
        assertOnlyConflictsBesidesWins(results);
        assertThat(wins(results)).isEqualTo(3);
        Set<String> assigned = new HashSet<>();
        results.stream().filter(r -> r instanceof ReservationView).forEach(r -> assigned.addAll(((ReservationView) r).unitCodes()));
        assertThat(assigned).containsExactlyInAnyOrder("S1", "S2", "S3");
    }

    @Test
    void timeSlotLastPlacesExactlyCapacityWinners() throws Exception {
        Instant s = daysFromNow(3);
        UUID clinic = createResource("""
                {"type":"APPOINTMENT","name":"Clinic","price":{"amount":"1","currency":"USD"},
                 "inventory":{"slots":[{"startsAt":"%s","endsAt":"%s","capacity":3}]}}""".formatted(s, s.plusSeconds(1800)));
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, clinic,
                new AllocationRequest(1, List.of(), null, s, null)));
        assertOnlyConflictsBesidesWins(results);
        assertThat(wins(results)).isEqualTo(3);
        assertThat(count("SELECT reserved FROM time_slot WHERE resource_id = ?", clinic)).isEqualTo(3);
    }

    @Test
    void timeRangeOverlappingRequestsExactlyOneWinner() throws Exception {
        UUID hotel = createUnitResource("HOTEL_ROOM", "301");
        Instant base = daysFromNow(30);
        // every request contains night base+2d .. base+3d, with varying start/end -> all pairwise overlapping
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, hotel,
                new AllocationRequest(1, List.of("301"), null,
                        base.plus(Duration.ofDays(i % 3)), base.plus(Duration.ofDays(3 + i % 2)))));
        assertOnlyConflictsBesidesWins(results);
        assertThat(wins(results)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    void timeRangeNonOverlappingRequestsAllSucceed() throws Exception {
        UUID hotel = createUnitResource("HOTEL_ROOM", "302");
        Instant base = daysFromNow(40);
        List<Object> results = runConcurrently(10, i -> () -> holdVia("u" + i, hotel,
                new AllocationRequest(1, List.of("302"), null, base.plus(Duration.ofDays(i)), base.plus(Duration.ofDays(i + 1)))));
        assertThat(wins(results)).isEqualTo(10);
    }

    @Test
    void timeRangeAnyUnitExactlyRoomCountWinners() throws Exception {
        UUID hotel = createUnitResource("HOTEL_ROOM", "401", "402", "403");
        Instant a = daysFromNow(50), b = daysFromNow(52);
        List<Object> results = runConcurrently(THREADS, i -> () -> holdVia("u" + i, hotel,
                new AllocationRequest(1, List.of(), null, a, b)));
        assertOnlyConflictsBesidesWins(results);
        assertThat(wins(results)).isEqualTo(3);
        assertThat(count("SELECT count(DISTINCT unit_id) FROM range_allocation WHERE status = 'ACTIVE'")).isEqualTo(3);
    }

    @Test
    void multiUnitRaceLoserLeavesNoPartialAllocation() throws Exception {
        for (int round = 0; round < 5; round++) {
            cleanTables();
            UUID hotel = createUnitResource("HOTEL_ROOM", "A", "B", "C");
            Instant a = daysFromNow(60), b = daysFromNow(61);
            List<Object> results = runConcurrently(2, i -> () -> holdVia("u" + i, hotel,
                    new AllocationRequest(2, i == 0 ? List.of("A", "B") : List.of("B", "C"), null, a, b)));
            assertOnlyConflictsBesidesWins(results);
            assertThat(wins(results)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM reservation")).isEqualTo(1);
        }
    }

    @Test
    void confirmVersusExpiryNeverBothWin() throws Exception {
        int rounds = 15;
        UUID ga = createCapacityResource(rounds, "5.00");
        fakePayment.setLatency(Duration.ofMillis(15));
        int confirmed = 0;
        for (int round = 0; round < rounds; round++) {
            ReservationView v = (ReservationView) holdVia("alice", ga, new AllocationRequest(1, List.of(), null, null, null));
            UUID id = v.id();
            int offsetMs = ThreadLocalRandom.current().nextInt(0, 30);
            jdbc.update("UPDATE reservation SET hold_expires_at = now() + make_interval(secs => ?) WHERE id = ?",
                    offsetMs / 1000.0, id);
            List<Object> results = runConcurrently(2, i -> i == 0
                    ? () -> confirmations.confirm(CurrentUser.user("alice"), id, null)
                    : () -> {
                        long end = System.nanoTime() + Duration.ofMillis(120).toNanos();
                        int n = 0;
                        while (System.nanoTime() < end) {
                            n += sweeper.sweep();
                        }
                        return n;
                    });
            Object confirmResult = results.get(0);
            assertThat(confirmResult).isInstanceOfAny(com.reservation.engine.application.ConfirmResult.class,
                    HoldExpiredException.class, InvalidStateTransitionException.class);
            String status = statusOf(id);
            assertThat(status).isIn("CONFIRMED", "EXPIRED");
            int active = count("SELECT count(*) FROM capacity_allocation WHERE reservation_id = ? AND status='ACTIVE'", id);
            int charged = count("SELECT count(*) FROM payment_transaction WHERE reservation_id = ? AND type='CHARGE' AND status='SUCCEEDED'", id);
            if (status.equals("CONFIRMED")) {
                confirmed++;
                assertThat(active).isEqualTo(1);
                assertThat(charged).isEqualTo(1);
            } else {
                assertThat(active).isZero();
                assertThat(charged).isZero();
            }
        }
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(confirmed);
    }

    @Test
    void cancelVersusConfirmKeepsInventoryAndPaymentsConsistent() throws Exception {
        int rounds = 15;
        UUID ga = createCapacityResource(rounds, "5.00");
        fakePayment.setLatency(Duration.ofMillis(10));
        int live = 0;
        for (int round = 0; round < rounds; round++) {
            ReservationView v = (ReservationView) holdVia("alice", ga, new AllocationRequest(1, List.of(), null, null, null));
            UUID id = v.id();
            List<Object> results = runConcurrently(2, i -> i == 0
                    ? () -> confirmations.confirm(CurrentUser.user("alice"), id, null)
                    : () -> reservations.cancel(CurrentUser.user("alice"), id, "race"));
            assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOfAny(
                    com.reservation.engine.application.ConfirmResult.class, ReservationView.class, DomainException.class));
            String status = statusOf(id);
            assertThat(status).isIn("CONFIRMED", "CANCELLED");
            int active = count("SELECT count(*) FROM capacity_allocation WHERE reservation_id = ? AND status='ACTIVE'", id);
            int charged = count("SELECT count(*) FROM payment_transaction WHERE reservation_id = ? AND type='CHARGE' AND status='SUCCEEDED'", id);
            int refunds = count("SELECT count(*) FROM payment_transaction WHERE reservation_id = ? AND type='REFUND'", id);
            if (status.equals("CONFIRMED")) {
                live++;
                assertThat(active).isEqualTo(1);
                assertThat(charged).isEqualTo(1);
                assertThat(results.get(1)).isInstanceOf(InvalidStateTransitionException.class);
            } else {
                assertThat(active).isZero();
                assertThat(refunds).isEqualTo(charged); // charged -> refund queued; never charged -> no refund
            }
        }
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(live);
        assertThat(count("SELECT count(*) FROM reservation WHERE status = 'PAYMENT_PENDING'")).isZero();
    }

    @Test
    void concurrentRetriesWithSameIdempotencyKeyCreateExactlyOneReservation() throws Exception {
        UUID ga = createCapacityResource(100, "1.00");
        String idemKey = key();
        String json = toJson(Map.of("resourceId", ga, "quantity", 1));
        List<Object> results = runConcurrently(THREADS, i -> () -> mvc.perform(post("/api/v1/reservations")
                .header("Authorization", TestJwt.bearer("alice", "USER"))
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn());
        Set<String> ids = new HashSet<>();
        for (Object r : results) {
            MvcResult res = (MvcResult) r;
            int st = res.getResponse().getStatus();
            assertThat(st).isIn(201, 409);
            if (st == 201) {
                ids.add(mapper.readTree(res.getResponse().getContentAsString()).get("id").asText());
            } else {
                assertThat(res.getResponse().getContentAsString()).contains("IDEMPOTENCY_REQUEST_IN_PROGRESS");
            }
        }
        assertThat(ids).hasSize(1);
        assertThat(count("SELECT count(*) FROM reservation")).isEqualTo(1);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(1);
        // a later retry replays the stored response
        MvcResult replay = mvc.perform(post("/api/v1/reservations")
                .header("Authorization", TestJwt.bearer("alice", "USER")).header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(mapper.readTree(replay.getResponse().getContentAsString()).get("id").asText()).isEqualTo(ids.iterator().next());
    }

    @Test
    void concurrentSameKeyWithConflictingPayloadsYieldsOneEffect() throws Exception {
        UUID ga = createCapacityResource(100, "1.00");
        String idemKey = key();
        List<Object> results = runConcurrently(THREADS, i -> () -> mvc.perform(post("/api/v1/reservations")
                .header("Authorization", TestJwt.bearer("alice", "USER"))
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("resourceId", ga, "quantity", 1 + (i % 2))))).andReturn());
        for (Object r : results) {
            assertThat(((MvcResult) r).getResponse().getStatus()).isIn(201, 409, 422);
        }
        assertThat(count("SELECT count(*) FROM reservation")).isEqualTo(1);
        JsonNode winner = mapper.readTree(jdbc.queryForObject(
                "SELECT response_body::text FROM idempotency_record WHERE idem_key = ?", String.class, idemKey));
        int q = winner.get("quantity").asInt();
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(q);
        // the losing payload is now deterministically rejected with 422
        mvc.perform(post("/api/v1/reservations").header("Authorization", TestJwt.bearer("alice", "USER"))
                        .header("Idempotency-Key", idemKey).contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(Map.of("resourceId", ga, "quantity", q == 1 ? 2 : 1))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/reservations").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.totalElements").value(1));
    }

    private void cleanTables() {
        jdbc.execute("""
                TRUNCATE idempotency_record, outbox_event, payment_transaction, reservation_audit,
                         range_allocation, slot_allocation, unit_allocation, capacity_allocation,
                         time_slot, inventory_unit, capacity_inventory, reservation, resource
                RESTART IDENTITY CASCADE""");
    }
}
