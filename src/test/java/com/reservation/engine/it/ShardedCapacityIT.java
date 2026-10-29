package com.reservation.engine.it;

import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.application.ReservationService;
import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.support.AbstractPostgresIT;
import com.reservation.engine.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Flash-sale mode: CAPACITY sharded into N bucket rows. */
class ShardedCapacityIT extends AbstractPostgresIT {

    @Autowired
    ReservationService reservations;
    @Autowired
    TransactionTemplate tx;

    private UUID sharded(int capacity, int buckets) throws Exception {
        return createResource("""
                {"type":"EVENT_GA","name":"Flash sale","price":{"amount":"1.00","currency":"INR"},
                 "inventory":{"capacity":%d,"buckets":%d}}""".formatted(capacity, buckets));
    }

    private int bucketSum(String column, UUID resource) {
        return count("SELECT COALESCE(SUM(" + column + "),0) FROM capacity_bucket WHERE resource_id = ?", resource);
    }

    @Test
    void provisioningSplitsCapacityEvenlyAndAvailabilityIsTheSum() throws Exception {
        UUID r = sharded(10, 4);
        assertThat(jdbc.queryForList("SELECT capacity FROM capacity_bucket WHERE resource_id = ? ORDER BY bucket_no",
                Integer.class, r)).containsExactly(3, 3, 2, 2);
        assertThat(count("SELECT bucket_count FROM capacity_inventory WHERE resource_id = ?", r)).isEqualTo(4);
        holdOk("alice", Map.of("resourceId", r, "quantity", 3));
        mvc.perform(get("/api/v1/resources/{id}/availability", r).header("Authorization", TestJwt.bearer("alice", "USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacity").value(10))
                .andExpect(jsonPath("$.availableQuantity").value(7));
        // the single parent counter row is not touched in sharded mode (that is the point)
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", r)).isZero();
    }

    @Test
    void releaseReturnsQuantityToTheSourceBucket() throws Exception {
        UUID r = sharded(8, 4);
        UUID id = holdOk("alice", Map.of("resourceId", r, "quantity", 2));
        Integer bucket = jdbc.queryForObject("SELECT bucket_no FROM capacity_allocation WHERE reservation_id = ?", Integer.class, id);
        assertThat(count("SELECT reserved FROM capacity_bucket WHERE resource_id = ? AND bucket_no = ?", r, bucket)).isEqualTo(2);
        cancel("alice", id, key()).andExpect(status().isOk());
        assertThat(bucketSum("reserved", r)).isZero();
        assertThat(count("SELECT count(*) FROM capacity_allocation WHERE status = 'RELEASED'")).isEqualTo(1);
    }

    @Test
    void requestLargerThanAnyBucketIsSplitAndReleasedPerBucket() throws Exception {
        UUID r = sharded(8, 4); // 2 per bucket
        UUID id = holdOk("alice", Map.of("resourceId", r, "quantity", 5));
        assertThat(count("SELECT count(*) FROM capacity_allocation WHERE reservation_id = ? AND status = 'ACTIVE'", id)).isEqualTo(3);
        assertThat(count("SELECT SUM(quantity) FROM capacity_allocation WHERE reservation_id = ?", id)).isEqualTo(5);
        assertThat(bucketSum("reserved", r)).isEqualTo(5);
        hold("bob", key(), Map.of("resourceId", r, "quantity", 4)).andExpect(status().isConflict());
        holdOk("bob", Map.of("resourceId", r, "quantity", 3)); // exactly the rest, spread over buckets
        assertThat(bucketSum("reserved", r)).isEqualTo(8);
        hold("carol", key(), Map.of("resourceId", r, "quantity", 1)).andExpect(status().isConflict());

        cancel("alice", id, key()).andExpect(status().isOk());
        assertThat(bucketSum("reserved", r)).isEqualTo(3);
    }

    @Test
    void topUpKeepsLayoutAndLayoutCannotChange() throws Exception {
        UUID r = sharded(4, 2);
        mvc.perform(post("/api/v1/resources/{id}/inventory", r).header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":3}")).andExpect(status().is2xxSuccessful());
        assertThat(jdbc.queryForList("SELECT capacity FROM capacity_bucket WHERE resource_id = ? ORDER BY bucket_no",
                Integer.class, r)).containsExactly(4, 3);
        assertThat(count("SELECT capacity FROM capacity_inventory WHERE resource_id = ?", r)).isEqualTo(7);
        mvc.perform(post("/api/v1/resources/{id}/inventory", r).header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":1,\"buckets\":8}"))
                .andExpect(status().isUnprocessableEntity());
        UUID hotel = createUnitResource("HOTEL_ROOM", "1");
        mvc.perform(post("/api/v1/resources/{id}/inventory", hotel).header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"unitCodes\":[\"2\"],\"buckets\":2}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void databaseRejectsOversoldBucketAndBucketSumMismatch() throws Exception {
        UUID r = sharded(4, 2);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE capacity_bucket SET reserved = 3 WHERE resource_id = ? AND bucket_no = 0", r))
                .rootCause().hasMessageContaining("chk_bucket_not_oversold");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update(
                "UPDATE capacity_bucket SET capacity = capacity + 1 WHERE resource_id = ? AND bucket_no = 0", r)))
                .rootCause().hasMessageContaining("bucket sum");
    }

    @Test
    void concurrentHoldsNeverOversellAcrossBuckets() throws Exception {
        int capacity = 60;
        UUID r = sharded(capacity, 8);
        int threads = 28;
        List<Object> results = runConcurrently(threads, i -> () -> {
            int won = 0;
            for (int k = 0; k < 6; k++) {
                int q = 1 + ThreadLocalRandom.current().nextInt(3);
                try {
                    reservations.hold(CurrentUser.user("u" + i),
                            new ReservationService.HoldCommand(r, new AllocationRequest(q, List.of(), null, null, null)));
                    won += q;
                } catch (InventoryUnavailableException e) {
                    // expected once sold out
                }
            }
            return won;
        });
        assertThat(results).allSatisfy(x -> assertThat(x).isInstanceOf(Integer.class));
        int sold = results.stream().mapToInt(x -> (Integer) x).sum();
        assertThat(sold).isLessThanOrEqualTo(capacity);
        assertThat(bucketSum("reserved", r)).isEqualTo(sold);
        // nothing was lost either: the remainder (if any) can still be sold one by one, then exactly capacity is sold
        while (true) {
            try {
                reservations.hold(CurrentUser.user("late"),
                        new ReservationService.HoldCommand(r, new AllocationRequest(1, List.of(), null, null, null)));
                sold++;
            } catch (InventoryUnavailableException e) {
                break;
            }
        }
        assertThat(sold).isEqualTo(capacity);
        assertThat(count("SELECT SUM(quantity) FROM capacity_allocation WHERE resource_id = ? AND status = 'ACTIVE'", r)).isEqualTo(sold);
        assertThat(count("SELECT count(*) FROM capacity_bucket WHERE resource_id = ? AND reserved > capacity", r)).isZero();
    }

    @Test
    void singleRowModeIsUnchanged() throws Exception {
        UUID r = createCapacityResource(3, "1.00");
        holdOk("alice", Map.of("resourceId", r, "quantity", 3));
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", r)).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM capacity_bucket")).isZero();
        assertThat(count("SELECT count(*) FROM capacity_allocation WHERE bucket_no IS NULL")).isEqualTo(1);
    }

}
