package com.reservation.engine.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.reservation.engine.api.dto.CreateReservationRequest;
import com.reservation.engine.idempotency.RequestHasher;
import com.reservation.engine.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyIT extends AbstractPostgresIT {

    @Autowired
    RequestHasher hasher;

    @Test
    void retryReplaysOriginalResponseWithoutSecondEffect() throws Exception {
        UUID ga = createCapacityResource(10, "1.00");
        String k = key();
        JsonNode first = body(hold("alice", k, Map.of("resourceId", ga, "quantity", 2)).andExpect(status().isCreated()));
        JsonNode second = body(hold("alice", k, Map.of("resourceId", ga, "quantity", 2))
                .andExpect(status().isCreated()).andExpect(header().string("Idempotent-Replayed", "true")));
        assertThat(second.get("id")).isEqualTo(first.get("id"));
        assertThat(count("SELECT count(*) FROM reservation")).isEqualTo(1);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(2);
    }

    @Test
    void sameKeyDifferentPayloadIs422() throws Exception {
        UUID ga = createCapacityResource(10, "1.00");
        String k = key();
        hold("alice", k, Map.of("resourceId", ga, "quantity", 1)).andExpect(status().isCreated());
        hold("alice", k, Map.of("resourceId", ga, "quantity", 2))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameKeyOnDifferentOperationIs422() throws Exception {
        UUID ga = createCapacityResource(10, "1.00");
        String k = key();
        UUID id = UUID.fromString(body(hold("alice", k, Map.of("resourceId", ga)).andExpect(status().isCreated())).get("id").asText());
        confirm("alice", id, k, null).andExpect(status().isUnprocessableEntity());
        assertThat(statusOf(id)).isEqualTo("HELD");
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        UUID ga = createCapacityResource(10, "1.00");
        String k = key();
        hold("alice", k, Map.of("resourceId", ga)).andExpect(status().isCreated());
        hold("bob", k, Map.of("resourceId", ga)).andExpect(status().isCreated()).andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(count("SELECT count(*) FROM reservation")).isEqualTo(2);
    }

    @Test
    void failedAttemptReleasesKeySoRetryCanSucceed() throws Exception {
        UUID ga = createCapacityResource(0, "1.00");
        String k = key();
        hold("alice", k, Map.of("resourceId", ga)).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
        jdbc.update("UPDATE capacity_inventory SET capacity = 1 WHERE resource_id = ?", ga);
        hold("alice", k, Map.of("resourceId", ga)).andExpect(status().isCreated());
    }

    @Test
    void confirmAndCancelReplays() throws Exception {
        UUID ga = createCapacityResource(10, "9.00");
        UUID id = holdOk("alice", Map.of("resourceId", ga));
        String ck = key();
        confirm("alice", id, ck, null).andExpect(status().isOk());
        confirm("alice", id, ck, null).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE type='CHARGE'")).isEqualTo(1);
        String xk = key();
        cancel("alice", id, xk).andExpect(status().isOk());
        cancel("alice", id, xk).andExpect(status().isOk()).andExpect(header().string("Idempotent-Replayed", "true"));
        cancel("alice", id, key()).andExpect(status().isConflict()); // a NEW cancel is an invalid transition
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE type='REFUND'")).isEqualTo(1);
    }

    @Test
    void inProgressKeyIs409AndStaleClaimIsReclaimed() throws Exception {
        UUID ga = createCapacityResource(10, "1.00");
        String k = key();
        String hash = hasher.hash("CREATE_RESERVATION", "", new CreateReservationRequest(ga, 1, null, null, null, null));
        jdbc.update("""
                INSERT INTO idempotency_record (user_id, idem_key, operation, request_hash, status, created_at, updated_at, expires_at)
                VALUES ('alice', ?, 'CREATE_RESERVATION', ?, 'IN_PROGRESS', now(), now(), now() + interval '1 day')""", k, hash);
        hold("alice", k, "{\"resourceId\":\"" + ga + "\",\"quantity\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        // simulate a crashed owner: claim older than in-progress-timeout can be taken over
        jdbc.update("UPDATE idempotency_record SET updated_at = now() - interval '1 hour' WHERE idem_key = ?", k);
        hold("alice", k, "{\"resourceId\":\"" + ga + "\",\"quantity\":1}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT status FROM idempotency_record WHERE idem_key = ?", String.class, k))
                .isEqualTo("COMPLETED");
    }
}
