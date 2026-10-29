package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.application.ExpirySweeper;
import com.reservation.engine.support.AbstractPostgresIT;
import com.reservation.engine.support.TestJwt;
import com.reservation.engine.waitlist.WaitlistPromoter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Waitlist + auto-promotion against real PostgreSQL. The promoter is driven explicitly (scheduling is off in tests). */
class WaitlistIT extends AbstractPostgresIT {

    @Autowired
    WaitlistPromoter promoter;
    @Autowired
    ExpirySweeper sweeper;

    private ResultActions join(String user, String idemKey, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/waitlist")
                .header("Authorization", TestJwt.bearer(user, "USER"))
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body)));
    }

    private UUID joinOk(String user, Map<String, Object> body) throws Exception {
        return UUID.fromString(body(join(user, key(), body).andExpect(status().isCreated())).get("id").asText());
    }

    private String entryStatus(UUID entry) {
        return jdbc.queryForObject("SELECT status FROM waitlist_entry WHERE id = ?", String.class, entry);
    }

    private UUID promotedReservation(UUID entry) {
        return jdbc.queryForObject("SELECT promoted_reservation_id FROM waitlist_entry WHERE id = ?", UUID.class, entry);
    }

    private void drainPromoter() {
        while (promoter.runOnce().signals() > 0) {
            // keep going until no pending signals remain
        }
    }

    @Test
    void waiterIsPromotedToHeldReservationWhenInventoryIsCancelled() throws Exception {
        UUID ga = createCapacityResource(1, "50.00");
        UUID aliceRes = holdOk("alice", Map.of("resourceId", ga));
        hold("bob", key(), Map.of("resourceId", ga)).andExpect(status().isConflict());

        UUID entry = joinOk("bob", Map.of("resourceId", ga));
        drainPromoter();
        assertThat(entryStatus(entry)).isEqualTo("WAITING"); // nothing free yet

        cancel("alice", aliceRes, key()).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM waitlist_signal WHERE processed_at IS NULL AND reason = 'RELEASED'")).isEqualTo(1);
        drainPromoter();

        assertThat(entryStatus(entry)).isEqualTo("PROMOTED");
        UUID bobRes = promotedReservation(entry);
        assertThat(statusOf(bobRes)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT user_id FROM reservation WHERE id = ?", String.class, bobRes)).isEqualTo("bob");
        assertThat(count("SELECT count(*) FROM reservation WHERE id = ? AND hold_expires_at > now() + interval '5 minutes'", bobRes))
                .as("promoted hold gets its own fresh TTL").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'WaitlistPromoted'")).isEqualTo(1);

        JsonNode view = body(mvc.perform(get("/api/v1/waitlist/{id}", entry)
                .header("Authorization", TestJwt.bearer("bob", "USER"))).andExpect(status().isOk()));
        assertThat(view.get("promotedReservationId").asText()).isEqualTo(bobRes.toString());
        // bob confirms the promoted hold through the normal reservation API
        confirm("bob", bobRes, key(), null).andExpect(status().isOk());
    }

    @Test
    void promotionIsFifoByRequestTime() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        UUID held = holdOk("holder", Map.of("resourceId", ga));
        List<UUID> entries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            entries.add(joinOk("w" + i, Map.of("resourceId", ga)));
        }
        drainPromoter();

        cancel("holder", held, key()).andExpect(status().isOk());
        drainPromoter();
        assertThat(entries.stream().map(this::entryStatus).toList())
                .containsExactly("PROMOTED", "WAITING", "WAITING", "WAITING");

        cancel("w0", promotedReservation(entries.get(0)), key()).andExpect(status().isOk());
        drainPromoter();
        assertThat(entries.stream().map(this::entryStatus).toList())
                .containsExactly("PROMOTED", "PROMOTED", "WAITING", "WAITING");
    }

    @Test
    void expiryOfAHoldPromotesTheNextWaiter() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        UUID held = holdOk("holder", Map.of("resourceId", ga));
        UUID entry = joinOk("bob", Map.of("resourceId", ga));
        forceHoldExpiry(held);
        assertThat(sweeper.sweep()).isEqualTo(1);
        drainPromoter();
        assertThat(entryStatus(entry)).isEqualTo("PROMOTED");
    }

    @Test
    void nextEligibleWaiterIsPromotedWhenTheHeadCannotBeSatisfied() throws Exception {
        UUID ga = createCapacityResource(3, "5.00");
        UUID big = holdOk("holder", Map.of("resourceId", ga, "quantity", 3));
        UUID wantsThree = joinOk("w1", Map.of("resourceId", ga, "quantity", 3));
        UUID wantsOne = joinOk("w2", Map.of("resourceId", ga, "quantity", 1));
        // release only 1 seat: holder cancels and someone else immediately takes 2
        cancel("holder", big, key()).andExpect(status().isOk());
        holdOk("other", Map.of("resourceId", ga, "quantity", 2));
        drainPromoter();
        assertThat(entryStatus(wantsThree)).isEqualTo("WAITING");
        assertThat(entryStatus(wantsOne)).isEqualTo("PROMOTED");
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(3);
    }

    @Test
    void joiningWhileInventoryIsFreePromotesOnTheNextRun() throws Exception {
        UUID ga = createCapacityResource(2, "5.00");
        UUID entry = joinOk("bob", Map.of("resourceId", ga));
        drainPromoter();
        assertThat(entryStatus(entry)).isEqualTo("PROMOTED");
    }

    @Test
    void specificUnitWaitersOnlyGetThatUnit() throws Exception {
        UUID flight = createUnitResource("FLIGHT", "1A", "1B");
        UUID r1a = holdOk("x", Map.of("resourceId", flight, "unitCodes", List.of("1A")));
        UUID r1b = holdOk("y", Map.of("resourceId", flight, "unitCodes", List.of("1B")));
        UUID wants1A = joinOk("bob", Map.of("resourceId", flight, "unitCodes", List.of("1A")));
        UUID wantsAny = joinOk("carol", Map.of("resourceId", flight, "quantity", 1));

        cancel("y", r1b, key()).andExpect(status().isOk()); // 1B freed: bob can't use it, carol can
        drainPromoter();
        assertThat(entryStatus(wants1A)).isEqualTo("WAITING");
        assertThat(entryStatus(wantsAny)).isEqualTo("PROMOTED");

        cancel("x", r1a, key()).andExpect(status().isOk());
        drainPromoter();
        assertThat(entryStatus(wants1A)).isEqualTo("PROMOTED");
        assertThat(jdbc.queryForObject("SELECT unit_codes::text FROM reservation WHERE id = ?", String.class,
                promotedReservation(wants1A))).contains("1A");
    }

    @Test
    void expiredAndCancelledEntriesAreNeverPromoted() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        UUID held = holdOk("holder", Map.of("resourceId", ga));
        UUID expiring = joinOk("w1", Map.of("resourceId", ga, "maxWaitMinutes", 5));
        UUID leaving = joinOk("w2", Map.of("resourceId", ga));
        UUID staying = joinOk("w3", Map.of("resourceId", ga));

        mvc.perform(post("/api/v1/waitlist/{id}/cancel", leaving).header("Authorization", TestJwt.bearer("w2", "USER"))
                .header("Idempotency-Key", key())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/waitlist/{id}/cancel", leaving).header("Authorization", TestJwt.bearer("w3", "USER"))
                .header("Idempotency-Key", key())).andExpect(status().isForbidden());
        jdbc.update("UPDATE waitlist_entry SET expires_at = requested_at + interval '1 millisecond' WHERE id = ?", expiring);
        assertThat(promoter.expireEntries()).isEqualTo(1);

        cancel("holder", held, key()).andExpect(status().isOk());
        drainPromoter();
        assertThat(entryStatus(expiring)).isEqualTo("EXPIRED");
        assertThat(entryStatus(leaving)).isEqualTo("CANCELLED");
        assertThat(entryStatus(staying)).isEqualTo("PROMOTED");
    }

    @Test
    void joinIsIdempotentAndValidatedLikeAHold() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        String k = key();
        JsonNode first = body(join("bob", k, Map.of("resourceId", ga)).andExpect(status().isCreated()));
        join("bob", k, Map.of("resourceId", ga)).andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(first.get("id").asText()));
        assertThat(count("SELECT count(*) FROM waitlist_entry")).isEqualTo(1);
        join("bob", key(), Map.of("resourceId", ga, "unitCodes", List.of("X"))).andExpect(status().isUnprocessableEntity());
        join("bob", key(), Map.of("resourceId", UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void releasesWithoutWaitersWriteNoSignals() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        UUID r = holdOk("alice", Map.of("resourceId", ga));
        cancel("alice", r, key()).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM waitlist_signal")).isZero();
    }

    /**
     * 5 seats held, 20 waiters. The 5 holders cancel concurrently while 6 promoter threads run concurrently.
     * Exactly 5 entries get promoted (once each), capacity is never exceeded, nothing is left unprocessed.
     */
    @Test
    void concurrentReleasesAndPromotersPromoteExactlyOncePerReleasedSeat() throws Exception {
        int seats = 5;
        UUID ga = createCapacityResource(seats, "5.00");
        List<UUID> held = new ArrayList<>();
        for (int i = 0; i < seats; i++) {
            held.add(holdOk("h" + i, Map.of("resourceId", ga)));
        }
        List<UUID> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(joinOk("w" + i, Map.of("resourceId", ga)));
        }
        drainPromoter(); // consume JOINED signals while sold out

        AtomicInteger promotedByRuns = new AtomicInteger();
        List<Object> results = runConcurrently(seats + 6, i -> () -> {
            if (i < seats) {
                return cancel("h" + i, held.get(i), key()).andReturn().getResponse().getStatus();
            }
            for (int round = 0; round < 30; round++) {
                promotedByRuns.addAndGet(promoter.runOnce().promoted());
                Thread.sleep(5);
            }
            return "promoter";
        });
        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        drainPromoter();

        List<String> statuses = entries.stream().map(this::entryStatus).toList();
        assertThat(statuses.stream().filter("PROMOTED"::equals).count()).isEqualTo(seats);
        assertThat(count("SELECT count(DISTINCT promoted_reservation_id) FROM waitlist_entry WHERE status = 'PROMOTED'")).isEqualTo(seats);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(seats);
        assertThat(count("SELECT count(*) FROM reservation WHERE resource_id = ? AND status = 'HELD'", ga)).isEqualTo(seats);
        assertThat(count("SELECT count(*) FROM waitlist_signal WHERE processed_at IS NULL")).isZero();
        assertThat(count("SELECT count(*) FROM outbox_event WHERE event_type = 'WaitlistPromoted'")).isEqualTo(seats);
        assertThat(new HashSet<>(jdbc.queryForList(
                "SELECT user_id FROM reservation WHERE resource_id = ? AND status = 'HELD'", String.class, ga))).hasSize(seats);
    }

    @Test
    void manyConcurrentPromotersOnOneReleaseNeverDoublePromote() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        UUID held = holdOk("holder", Map.of("resourceId", ga));
        for (int i = 0; i < 10; i++) {
            joinOk("w" + i, Map.of("resourceId", ga));
        }
        drainPromoter();
        cancel("holder", held, key()).andExpect(status().isOk());
        // add extra signals so several promoters really work on the same resource at once
        for (int i = 0; i < 8; i++) {
            jdbc.update("INSERT INTO waitlist_signal (resource_id, reason, created_at) VALUES (?, 'TEST', now())", ga);
        }
        List<Object> results = runConcurrently(8, i -> () -> promoter.runOnce().promoted());
        assertThat(results).doesNotHaveAnyElementsOfTypes(Throwable.class);
        assertThat(results.stream().mapToInt(r -> (Integer) r).sum()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM waitlist_entry WHERE status = 'PROMOTED'")).isEqualTo(1);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(1);
    }

    @Test
    void rescanSignalsResourcesThatStillHaveWaiters() throws Exception {
        UUID ga = createCapacityResource(1, "5.00");
        joinOk("bob", Map.of("resourceId", ga));
        jdbc.update("UPDATE waitlist_signal SET processed_at = now()"); // simulate a lost signal
        assertThat(promoter.rescan()).isEqualTo(1);
        drainPromoter();
        assertThat(count("SELECT count(*) FROM waitlist_entry WHERE status = 'PROMOTED'")).isEqualTo(1);
    }
}
