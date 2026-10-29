package com.reservation.engine.it;

import com.reservation.engine.application.ExpirySweeper;
import com.reservation.engine.application.PaymentReconciliationJob;
import com.reservation.engine.idempotency.IdempotencyCleanupJob;
import com.reservation.engine.outbox.OutboxPublisherJob;
import com.reservation.engine.payment.FakePaymentAdapter;
import com.reservation.engine.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Background jobs: expiry sweeper, payment reconciliation, refunds, outbox publisher, idempotency cleanup. */
class LifecycleJobsIT extends AbstractPostgresIT {

    @Autowired
    ExpirySweeper sweeper;
    @Autowired
    PaymentReconciliationJob reconciliation;
    @Autowired
    OutboxPublisherJob outboxPublisher;
    @Autowired
    IdempotencyCleanupJob idempotencyCleanup;

    @Test
    void sweeperExpiresOnlyLapsedHeldReservations() throws Exception {
        UUID ga = createCapacityResource(10, "5.00");
        UUID lapsed = holdOk("alice", Map.of("resourceId", ga, "quantity", 2));
        UUID fresh = holdOk("alice", Map.of("resourceId", ga, "quantity", 1));
        UUID paying = holdOk("alice", Map.of("resourceId", ga, "quantity", 3));
        confirm("alice", paying, key(), "tok_error").andExpect(status().isAccepted()); // payment outcome unknown
        forceHoldExpiry(lapsed);
        forceHoldExpiry(paying);

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(statusOf(lapsed)).isEqualTo("EXPIRED");
        assertThat(statusOf(fresh)).isEqualTo("HELD");
        assertThat(statusOf(paying)).isEqualTo("PAYMENT_PENDING"); // never expired while payment is in flight
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type = 'ReservationExpired'", lapsed)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM reservation_audit WHERE reservation_id = ? AND to_status = 'EXPIRED'",
                String.class, lapsed)).isEqualTo("system");
        assertThat(sweeper.sweep()).isZero(); // idempotent
    }

    @Test
    void sweeperReleasesEveryInventoryModel() throws Exception {
        UUID flight = createUnitResource("FLIGHT", "1A");
        UUID hotel = createUnitResource("HOTEL_ROOM", "101");
        Instant slotStart = daysFromNow(2);
        UUID clinic = createResource("""
                {"type":"APPOINTMENT","name":"C","price":{"amount":"1","currency":"USD"},
                 "inventory":{"slots":[{"startsAt":"%s","endsAt":"%s","capacity":1}]}}""".formatted(slotStart, slotStart.plusSeconds(1800)));
        UUID a = holdOk("alice", Map.of("resourceId", flight, "unitCodes", new String[]{"1A"}));
        UUID b = holdOk("alice", Map.of("resourceId", hotel, "startsAt", daysFromNow(5).toString(), "endsAt", daysFromNow(6).toString()));
        UUID c = holdOk("alice", Map.of("resourceId", clinic, "startsAt", slotStart.toString()));
        forceHoldExpiry(a);
        forceHoldExpiry(b);
        forceHoldExpiry(c);
        assertThat(sweeper.sweep()).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM unit_allocation WHERE status='ACTIVE'")).isZero();
        assertThat(count("SELECT count(*) FROM range_allocation WHERE status='ACTIVE'")).isZero();
        assertThat(count("SELECT reserved FROM time_slot WHERE resource_id = ?", clinic)).isZero();
        // inventory is bookable again
        holdOk("bob", Map.of("resourceId", flight, "unitCodes", new String[]{"1A"}));
        holdOk("bob", Map.of("resourceId", clinic, "startsAt", slotStart.toString()));
    }

    @Test
    void reconciliationConfirmsReservationWhoseChargeActuallySucceeded() throws Exception {
        UUID ga = createCapacityResource(1, "20.00");
        UUID id = holdOk("alice", Map.of("resourceId", ga));
        confirm("alice", id, key(), "tok_unknown").andExpect(status().isAccepted());
        forceHoldExpiry(id); // even after the hold deadline, a successful charge must win
        var report = reconciliation.reconcile(Duration.ZERO);
        assertThat(report.chargesResolved()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("CONFIRMED");
        assertThat(count("SELECT count(*) FROM payment_transaction WHERE reservation_id = ? AND status='SUCCEEDED'", id)).isEqualTo(1);
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isEqualTo(1);
    }

    @Test
    void reconciliationLeavesTrulyUnknownPaymentsPending() throws Exception {
        UUID ga = createCapacityResource(1, "20.00");
        UUID id = holdOk("alice", Map.of("resourceId", ga));
        confirm("alice", id, key(), "tok_error").andExpect(status().isAccepted());
        var report = reconciliation.reconcile(Duration.ZERO);
        assertThat(report.chargesStillUnknown()).isEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("PAYMENT_PENDING");
    }

    @Test
    void declineAfterHoldDeadlineExpiresAndReleases() throws Exception {
        UUID ga = createCapacityResource(1, "20.00");
        UUID id = holdOk("alice", Map.of("resourceId", ga));
        jdbc.update("UPDATE reservation SET hold_expires_at = now() + interval '300 milliseconds' WHERE id = ?", id);
        fakePayment.setLatency(Duration.ofMillis(800));
        confirm("alice", id, key(), "tok_decline").andExpect(status().isPaymentRequired());
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(count("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", ga)).isZero();
    }

    @Test
    void refundsQueuedByCancellationAreProcessed() throws Exception {
        UUID ga = createCapacityResource(1, "30.00");
        UUID id = holdOk("alice", Map.of("resourceId", ga));
        confirm("alice", id, key(), null).andExpect(status().isOk());
        cancel("alice", id, key()).andExpect(status().isOk());
        var report = reconciliation.reconcile(Duration.ZERO);
        assertThat(report.refundsProcessed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transaction WHERE reservation_id = ? AND type='REFUND'",
                String.class, id)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? AND event_type='ReservationRefunded'", id)).isEqualTo(1);
        assertThat(reconciliation.reconcile(Duration.ZERO).refundsProcessed()).isZero();
    }

    @Test
    void outboxPublisherMarksEventsSent() throws Exception {
        UUID ga = createCapacityResource(5, "1.00");
        holdOk("alice", Map.of("resourceId", ga));
        holdOk("bob", Map.of("resourceId", ga));
        int pending = count("SELECT count(*) FROM outbox_event WHERE status='PENDING'");
        assertThat(pending).isEqualTo(2);
        assertThat(outboxPublisher.publishPending()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM outbox_event WHERE status='SENT' AND sent_at IS NOT NULL")).isEqualTo(2);
        assertThat(outboxPublisher.publishPending()).isZero();
    }

    @Test
    void outboxEventIsRolledBackTogetherWithFailedHold() throws Exception {
        UUID ga = createCapacityResource(1, "1.00");
        holdOk("alice", Map.of("resourceId", ga));
        hold("bob", key(), Map.of("resourceId", ga)).andExpect(status().isConflict());
        assertThat(count("SELECT count(*) FROM outbox_event")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reservation_audit")).isEqualTo(1);
    }

    @Test
    void idempotencyCleanupRemovesExpiredRecords() {
        jdbc.update("""
                INSERT INTO idempotency_record (user_id, idem_key, operation, request_hash, status, created_at, updated_at, expires_at)
                VALUES ('alice','old-key-123','CREATE_RESERVATION','h','IN_PROGRESS', now(), now(), now() - interval '1 minute')""");
        idempotencyCleanup.cleanup();
        assertThat(count("SELECT count(*) FROM idempotency_record")).isZero();
    }

    @Test
    void globalFakeModeIsHonoured() throws Exception {
        fakePayment.setMode(FakePaymentAdapter.Mode.DECLINE);
        UUID ga = createCapacityResource(1, "1.00");
        UUID id = holdOk("alice", Map.of("resourceId", ga));
        confirm("alice", id, key(), null).andExpect(status().isPaymentRequired());
        assertThat(statusOf(id)).isEqualTo("HELD");
    }
}
