package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.application.ExpirySweeper;
import com.reservation.engine.application.ItineraryJobs;
import com.reservation.engine.support.AbstractPostgresIT;
import com.reservation.engine.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Atomic multi-resource booking against real PostgreSQL. */
class ItineraryIT extends AbstractPostgresIT {

    @Autowired
    ItineraryJobs itineraryJobs;
    @Autowired
    ExpirySweeper sweeper;
    @Autowired
    com.reservation.engine.application.PaymentReconciliationJob reconciliation;

    private UUID bus;
    private UUID flight;
    private UUID hotel;
    private Instant checkIn;
    private Instant checkOut;

    @BeforeEach
    void resources() throws Exception {
        bus = createResource("""
                {"type":"BUS","name":"Airport shuttle","price":{"amount":"10.00","currency":"INR"},"inventory":{"capacity":5}}""");
        flight = createResource("""
                {"type":"FLIGHT","name":"6E-201","price":{"amount":"4000.00","currency":"INR"},
                 "inventory":{"unitCodes":["1A","1B","1C"]}}""");
        hotel = createResource("""
                {"type":"HOTEL_ROOM","name":"Goa Resort","price":{"amount":"3000.00","currency":"INR"},
                 "inventory":{"unitCodes":["101","102"]}}""");
        checkIn = daysFromNow(10);
        checkOut = daysFromNow(12);
    }

    private ResultActions createItinerary(String user, String idemKey, List<Map<String, Object>> items) throws Exception {
        return mvc.perform(post("/api/v1/itineraries")
                .header("Authorization", TestJwt.bearer(user, "USER"))
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("items", items))));
    }

    private List<Map<String, Object>> trip(String seat) {
        return List.of(
                Map.of("resourceId", flight, "unitCodes", List.of(seat)),
                Map.of("resourceId", hotel, "startsAt", checkIn.toString(), "endsAt", checkOut.toString()),
                Map.of("resourceId", bus, "quantity", 2));
    }

    private ResultActions itineraryAction(String user, UUID id, String action, String body) throws Exception {
        var req = post("/api/v1/itineraries/{id}/" + action, id)
                .header("Authorization", TestJwt.bearer(user, "USER"))
                .header("Idempotency-Key", key());
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req);
    }

    private int busReserved() {
        return count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", bus);
    }

    @Test
    void holdsEveryItemInOneUnitWithSharedDeadline() throws Exception {
        JsonNode it = body(createItinerary("alice", key(), trip("1A")).andExpect(status().isCreated()));

        assertThat(it.get("status").asText()).isEqualTo("HELD");
        assertThat(it.get("itemCount").asInt()).isEqualTo(3);
        // 4000 + 2 nights * 3000 + 2 * 10
        assertThat(it.get("total").get("amount").decimalValue()).isEqualByComparingTo("10020.00");
        assertThat(it.get("items")).hasSize(3);
        String deadline = it.get("holdExpiresAt").asText();
        for (JsonNode item : it.get("items")) {
            assertThat(item.get("status").asText()).isEqualTo("HELD");
            assertThat(item.get("itineraryId").asText()).isEqualTo(it.get("id").asText());
            assertThat(item.get("holdExpiresAt").asText()).isEqualTo(deadline);
        }
        assertThat(busReserved()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'ItineraryHeld'")).isEqualTo(1);

        UUID id = UUID.fromString(it.get("id").asText());
        mvc.perform(get("/api/v1/itineraries/{id}", id).header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
        mvc.perform(get("/api/v1/itineraries/{id}", id).header("Authorization", TestJwt.bearer("mallory", "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/itineraries").header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void partialFailureRollsBackEveryItem() throws Exception {
        holdOk("bob", Map.of("resourceId", flight, "unitCodes", List.of("1A"))); // seat 1A taken

        createItinerary("alice", key(), trip("1A"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVENTORY_UNAVAILABLE"));

        assertThat(count("SELECT count(*) FROM itinerary")).isZero();
        assertThat(count("SELECT count(*) FROM reservation WHERE user_id = 'alice'")).isZero();
        assertThat(busReserved()).isZero();
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isZero();
        assertThat(count("SELECT count(*) FROM capacity_allocation")).isZero();
        assertThat(count("SELECT count(*) FROM idempotency_record WHERE user_id = 'alice'")).isZero();

        // everything is still bookable afterwards
        createItinerary("alice", key(), trip("1B")).andExpect(status().isCreated());
    }

    @Test
    void validationFailureOfOneItemRejectsTheWholeItinerary() throws Exception {
        List<Map<String, Object>> items = List.of(
                Map.of("resourceId", bus, "quantity", 1),
                Map.of("resourceId", hotel, "startsAt", checkOut.toString(), "endsAt", checkIn.toString())); // inverted
        createItinerary("alice", key(), items).andExpect(status().isUnprocessableEntity());
        assertThat(count("SELECT count(*) FROM reservation")).isZero();

        UUID usd = createCapacityResource(5, "1.00");
        createItinerary("alice", key(), List.of(Map.of("resourceId", bus, "quantity", 1),
                Map.of("resourceId", usd, "quantity", 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("currency")));
    }

    @Test
    void retryWithSameIdempotencyKeyReplaysAndHoldsOnce() throws Exception {
        String k = key();
        JsonNode first = body(createItinerary("alice", k, trip("1A")).andExpect(status().isCreated()));
        JsonNode second = body(createItinerary("alice", k, trip("1A"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("SELECT count(*) FROM itinerary")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reservation")).isEqualTo(3);
        createItinerary("alice", k, trip("1B")).andExpect(status().isUnprocessableEntity()); // key reuse
    }

    @Test
    void confirmChargesOnceForTotalAndConfirmsAllItems() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());

        JsonNode confirmed = body(itineraryAction("alice", id, "confirm", null).andExpect(status().isOk()));
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'CONFIRMED'", id)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE itinerary_id = ? AND type = 'CHARGE' AND status = 'SUCCEEDED' AND amount = 10020.00", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE reservation_id IS NOT NULL")).isZero();

        // second confirm is a no-op success
        itineraryAction("alice", id, "confirm", null).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM payment_transaction")).isEqualTo(1);
    }

    @Test
    void itemsCannotBeConfirmedOrCancelledIndividually() throws Exception {
        JsonNode it = body(createItinerary("alice", key(), trip("1A")));
        UUID item = UUID.fromString(it.get("items").get(0).get("id").asText());
        confirm("alice", item, key(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANAGED_BY_ITINERARY"));
        cancel("alice", item, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANAGED_BY_ITINERARY"));
        assertThat(statusOf(item)).isEqualTo("HELD");
    }

    @Test
    void declinedPaymentRevertsTheWholeItineraryToHeld() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());
        itineraryAction("alice", id, "confirm", "{\"paymentMethodToken\":\"tok_decline\"}")
                .andExpect(status().isPaymentRequired());
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'HELD'", id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM itinerary WHERE id = ?", String.class, id)).isEqualTo("HELD");
        // can retry with a good card
        itineraryAction("alice", id, "confirm", null).andExpect(status().isOk());
    }

    @Test
    void unknownPaymentOutcomeIsResolvedByReconciliation() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());
        itineraryAction("alice", id, "confirm", "{\"paymentMethodToken\":\"tok_unknown\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        // single-reservation reconciler does not look at itinerary items
        assertThat(reconciliation.reconcile(java.time.Duration.ZERO).chargesResolved()).isZero();

        var report = itineraryJobs.reconcile(java.time.Duration.ZERO);
        assertThat(report.resolved()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM itinerary WHERE id = ?", String.class, id)).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'CONFIRMED'", id)).isEqualTo(3);
        assertThat(itineraryJobs.reconcile(java.time.Duration.ZERO).resolved()).isZero();
    }

    @Test
    void itineraryRefundIsProcessedByReconciliationWithItineraryEvent() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());
        itineraryAction("alice", id, "confirm", null).andExpect(status().isOk());
        itineraryAction("alice", id, "cancel", null).andExpect(status().isOk());
        assertThat(reconciliation.reconcile(java.time.Duration.ZERO).refundsProcessed()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE itinerary_id = ? AND type = 'REFUND' AND status = 'SUCCEEDED'", id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ItineraryRefunded'", id)).isEqualTo(1);
    }

    @Test
    void cancelReleasesEverythingAndQueuesOneRefund() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());
        itineraryAction("alice", id, "confirm", null).andExpect(status().isOk());

        itineraryAction("alice", id, "cancel", "{\"reason\":\"plans changed\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'CANCELLED'", id)).isEqualTo(3);
        assertThat(busReserved()).isZero();
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isZero();
        assertThat(count("SELECT count(*) FROM unit_allocation WHERE status = 'ACTIVE'")).isZero();
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE itinerary_id = ? AND type = 'REFUND'", id)).isEqualTo(1);
        // seat is bookable again
        holdOk("bob", Map.of("resourceId", flight, "unitCodes", List.of("1A")));
    }

    @Test
    void wholeItineraryExpiresTogetherAndOnlyThroughItsOwnSweeper() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 second' WHERE itinerary_id = ?", id);

        assertThat(sweeper.sweep()).isZero(); // per-reservation sweeper ignores itinerary items
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'HELD'", id)).isEqualTo(3);

        jdbc.update("UPDATE itinerary SET hold_expires_at = now() - interval '1 second' WHERE id = ?", id);
        assertThat(itineraryJobs.sweepExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM itinerary WHERE id = ?", String.class, id)).isEqualTo("EXPIRED");
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'EXPIRED'", id)).isEqualTo(3);
        assertThat(busReserved()).isZero();
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isZero();
        itineraryAction("alice", id, "confirm", null).andExpect(status().isConflict());
    }

    @Test
    void confirmAfterDeadlineExpiresEagerly() throws Exception {
        UUID id = UUID.fromString(body(createItinerary("alice", key(), trip("1A"))).get("id").asText());
        jdbc.update("UPDATE itinerary SET hold_expires_at = now() - interval '1 second' WHERE id = ?", id);
        itineraryAction("alice", id, "confirm", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id = ? AND status = 'EXPIRED'", id)).isEqualTo(3);
        assertThat(busReserved()).isZero();
    }

    /**
     * 24 users race for overlapping itineraries listing the same two hot resources in OPPOSITE orders.
     * Deterministic lock ordering (+ whole-tx retry) means: no deadlock errors surface, every failure is a clean
     * 409, and exactly capacity-many itineraries win.
     */
    @Test
    void concurrentOverlappingItinerariesNeverOverbookAndNeverFailWithDeadlocks() throws Exception {
        UUID a = createResource("""
                {"type":"BUS","name":"A","price":{"amount":"1.00","currency":"INR"},"inventory":{"capacity":7}}""");
        UUID b = createResource("""
                {"type":"EVENT_GA","name":"B","price":{"amount":"1.00","currency":"INR"},"inventory":{"capacity":7}}""");
        int threads = 24;
        List<Object> results = runConcurrently(threads, i -> () -> {
            List<Map<String, Object>> items = new ArrayList<>(List.of(
                    Map.of("resourceId", a, "quantity", 1),
                    Map.of("resourceId", b, "quantity", 1),
                    Map.of("resourceId", hotel, "startsAt", checkIn.toString(), "endsAt", checkOut.toString())));
            if (i % 2 == 1) {
                java.util.Collections.reverse(items);
            }
            MvcResult r = createItinerary("user-" + i, key(), items).andReturn();
            return r.getResponse().getStatus();
        });
        long created = results.stream().filter(r -> Integer.valueOf(201).equals(r)).count();
        long conflicts = results.stream().filter(r -> Integer.valueOf(409).equals(r)).count();
        assertThat(created + conflicts).as("only 201/409, no 500s: %s", results).isEqualTo(threads);
        assertThat(created).isEqualTo(2); // hotel has 2 rooms -> bottleneck
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", a)).isEqualTo(2);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", b)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reservation WHERE itinerary_id IS NULL")).isZero();
    }

    @Test
    void concurrentItinerariesOnScarceSeatsAndRooms() throws Exception {
        UUID seats = createResource("""
                {"type":"FLIGHT","name":"AI-101","price":{"amount":"1.00","currency":"INR"},
                 "inventory":{"unitCodes":["1A","1B","1C","1D","1E","1F"]}}""");
        UUID rooms = createResource("""
                {"type":"HOTEL_ROOM","name":"Big hotel","price":{"amount":"1.00","currency":"INR"},
                 "inventory":{"unitCodes":["1","2","3","4","5","6","7","8"]}}""");
        int threads = 20;
        List<Object> results = runConcurrently(threads, i -> () -> {
            List<Map<String, Object>> items = new ArrayList<>(List.of(
                    Map.of("resourceId", seats, "quantity", 1),
                    Map.of("resourceId", rooms, "startsAt", checkIn.toString(), "endsAt", checkOut.toString())));
            if (i % 2 == 0) {
                java.util.Collections.reverse(items);
            }
            return createItinerary("u" + i, key(), items).andReturn().getResponse().getStatus();
        });
        assertThat(results).allSatisfy(r -> assertThat(r).isIn(201, 409));
        long created = results.stream().filter(r -> Integer.valueOf(201).equals(r)).count();
        assertThat(created).isEqualTo(6); // 6 seats
        Set<String> seatsTaken = new HashSet<>(jdbc.queryForList(
                "SELECT u.unit_code FROM unit_allocation a JOIN inventory_unit u ON u.id = a.unit_id WHERE a.status = 'ACTIVE'",
                String.class));
        assertThat(seatsTaken).hasSize(6);
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status = 'ACTIVE'")).isEqualTo(6);
    }
}
