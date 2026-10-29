package com.reservation.engine.application;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.StateTransition;
import com.reservation.engine.domain.exception.HoldExpiredException;
import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItineraryRulesTest {

    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");

    private HoldExecutor.HoldPlan plan(Resource r, AllocationRequest req) {
        return new HoldExecutor.HoldPlan(r, null, null, req, Money.of("1.00", "INR"), Duration.ofMinutes(10));
    }

    @Test
    void lockOrderIsDeterministicRegardlessOfRequestOrder() {
        Resource a = new Resource("BUS", "CAPACITY", "a", null, Map.of(), Money.of("1", "INR"), now);
        Resource b = new Resource("HOTEL_ROOM", "TIME_RANGE", "b", null, Map.of(), Money.of("1", "INR"), now);
        List<HoldExecutor.HoldPlan> plans = new ArrayList<>(List.of(
                plan(a, new AllocationRequest(1, List.of(), null, null, null)),
                plan(b, new AllocationRequest(1, List.of(), null, now.plusSeconds(7200), now.plusSeconds(9000))),
                plan(b, new AllocationRequest(1, List.of(), null, now.plusSeconds(3600), now.plusSeconds(7000))),
                plan(b, new AllocationRequest(1, List.of("Z", "A"), null, now.plusSeconds(3600), now.plusSeconds(7000)))));
        List<HoldExecutor.HoldPlan> expected = plans.stream().sorted(ItineraryService.LOCK_ORDER).toList();
        for (int i = 0; i < 20; i++) {
            Collections.shuffle(plans);
            assertThat(plans.stream().sorted(ItineraryService.LOCK_ORDER).toList()).isEqualTo(expected);
        }
        // within one resource: earlier start first
        List<HoldExecutor.HoldPlan> forB = expected.stream().filter(p -> p.resource() == b).toList();
        assertThat(forB.get(0).request().startsAt()).isBeforeOrEqualTo(forB.get(2).request().startsAt());
    }

    @Test
    void itineraryReusesTheReservationStateMachine() {
        Itinerary it = Itinerary.hold("alice", 2, Money.of("10.00", "INR"), now.plusSeconds(600), now);
        it.startPayment("alice", now);
        it.revertToHeld("alice", "declined", now);
        it.confirm("alice", now);
        assertThat(it.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThatThrownBy(() -> it.expire("system", "late", now)).isInstanceOf(InvalidStateTransitionException.class);
        it.cancel("alice", "changed", now);
        assertThat(it.pullTransitions()).extracting(StateTransition::to).containsExactly(ReservationStatus.HELD,
                ReservationStatus.PAYMENT_PENDING, ReservationStatus.HELD, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED);
        assertThat(it.pullTransitions()).isEmpty();
    }

    @Test
    void expiredItineraryCannotStartPayment() {
        Itinerary it = Itinerary.hold("alice", 1, Money.of("10.00", "INR"), now, now.minusSeconds(60));
        assertThatThrownBy(() -> it.startPayment("alice", now)).isInstanceOf(HoldExpiredException.class);
    }

    @Test
    void lockConflictRetryRetriesOnlyLockFailures() {
        AtomicInteger calls = new AtomicInteger();
        String result = LockConflictRetry.run(3, "test", () -> {
            if (calls.incrementAndGet() < 3) {
                throw new CannotAcquireLockException("deadlock detected");
            }
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);

        AtomicInteger calls2 = new AtomicInteger();
        assertThatThrownBy(() -> LockConflictRetry.run(2, "test", () -> {
            calls2.incrementAndGet();
            throw new CannotAcquireLockException("deadlock detected");
        })).isInstanceOf(CannotAcquireLockException.class);
        assertThat(calls2.get()).isEqualTo(2);

        AtomicInteger calls3 = new AtomicInteger();
        assertThatThrownBy(() -> LockConflictRetry.run(3, "test", () -> {
            calls3.incrementAndGet();
            throw new DataIntegrityViolationException("other");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(calls3.get()).isEqualTo(1);
    }
}
