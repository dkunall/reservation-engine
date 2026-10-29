package com.reservation.engine.domain;

import com.reservation.engine.domain.exception.HoldExpiredException;
import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private final Instant now = Instant.parse("2030-01-01T10:00:00Z");
    private final Resource resource = new Resource("EVENT_GA", "CAPACITY", "Show", null, Map.of(),
            Money.of("10.00", "USD"), now);

    private Reservation newHold(Duration ttl) {
        return Reservation.hold("alice", resource, 2, null, null, null, Money.of("20.00", "USD"), now.plus(ttl), now);
    }

    @Test
    void holdRecordsInitialTransitionAndSnapshotsType() {
        Reservation r = newHold(Duration.ofMinutes(10));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(r.getResourceType()).isEqualTo("EVENT_GA");
        assertThat(r.getInventoryModel()).isEqualTo("CAPACITY");
        List<StateTransition> t = r.pullTransitions();
        assertThat(t).singleElement().satisfies(x -> {
            assertThat(x.from()).isNull();
            assertThat(x.to()).isEqualTo(ReservationStatus.HELD);
        });
        assertThat(r.pullTransitions()).isEmpty();
    }

    @Test
    void paymentFlowToConfirmedThenCancelled() {
        Reservation r = newHold(Duration.ofMinutes(10));
        r.startPayment("alice", now.plusSeconds(1));
        r.confirm("alice", now.plusSeconds(2));
        r.cancel("alice", "changed plans", now.plusSeconds(3));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(r.getConfirmedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(r.getCancelledAt()).isEqualTo(now.plusSeconds(3));
        assertThat(r.pullTransitions()).extracting(StateTransition::to).containsExactly(
                ReservationStatus.HELD, ReservationStatus.PAYMENT_PENDING, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELLED);
    }

    @Test
    void cannotStartPaymentOrConfirmAfterHoldExpiry() {
        Reservation r = newHold(Duration.ofMinutes(10));
        Instant late = now.plus(Duration.ofMinutes(10));
        assertThat(r.isHoldExpired(late)).isTrue();
        assertThatThrownBy(() -> r.startPayment("alice", late)).isInstanceOf(HoldExpiredException.class);
        assertThatThrownBy(() -> r.confirm("alice", late)).isInstanceOf(HoldExpiredException.class);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void paymentPendingCanBeConfirmedEvenAfterHoldDeadline() {
        Reservation r = newHold(Duration.ofMinutes(10));
        r.startPayment("alice", now.plusSeconds(5));
        r.confirm("reconciler", now.plus(Duration.ofHours(1)));
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void declinedPaymentReturnsToHeld() {
        Reservation r = newHold(Duration.ofMinutes(10));
        r.startPayment("alice", now);
        r.revertToHeld("alice", "declined", now);
        assertThat(r.getStatus()).isEqualTo(ReservationStatus.HELD);
    }

    @Test
    void expiredReservationCannotBeConfirmedOrCancelled() {
        Reservation r = newHold(Duration.ofMinutes(10));
        r.expire("system", "lapsed", now.plus(Duration.ofMinutes(11)));
        assertThat(r.getExpiredAt()).isNotNull();
        assertThatThrownBy(() -> r.confirm("alice", now)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> r.cancel("alice", null, now)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void paymentPendingCannotBeCancelled() {
        Reservation r = newHold(Duration.ofMinutes(10));
        r.startPayment("alice", now);
        assertThatThrownBy(() -> r.cancel("alice", null, now)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void ownership() {
        Reservation r = newHold(Duration.ofMinutes(10));
        assertThat(r.isOwnedBy("alice")).isTrue();
        assertThat(r.isOwnedBy("bob")).isFalse();
    }

    @Test
    void applyAllocationRecordsAssignedUnitsAndSlotPeriod() {
        Reservation r = newHold(Duration.ofMinutes(10));
        r.applyAllocation(new AllocationResult(1, List.of("12A"), null, now.plusSeconds(60), now.plusSeconds(120)));
        assertThat(r.getUnitCodes()).containsExactly("12A");
        assertThat(r.getQuantity()).isEqualTo(1);
        assertThat(r.getStartsAt()).isEqualTo(now.plusSeconds(60));
    }
}
