package com.reservation.engine.waitlist;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaitlistEntryTest {

    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");

    private WaitlistEntry entry() {
        return WaitlistEntry.join("bob", UUID.randomUUID(), new AllocationRequest(2, List.of("1A"), null, null, null),
                now.plusSeconds(3600), now);
    }

    @Test
    void promotionIsTerminalAndHappensOnce() {
        WaitlistEntry e = entry();
        UUID reservation = UUID.randomUUID();
        e.promote(reservation, now);
        assertThat(e.getStatus()).isEqualTo(WaitlistStatus.PROMOTED);
        assertThat(e.getPromotedReservationId()).isEqualTo(reservation);
        assertThatThrownBy(() -> e.promote(UUID.randomUUID(), now)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> e.cancel(now)).isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> e.expire("x", now)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void cancelledOrExpiredEntriesCannotBePromoted() {
        WaitlistEntry c = entry();
        c.cancel(now);
        assertThatThrownBy(() -> c.promote(UUID.randomUUID(), now)).isInstanceOf(InvalidStateTransitionException.class);
        WaitlistEntry x = entry();
        x.expire("timeout", now);
        assertThat(x.getLastError()).isEqualTo("timeout");
        assertThatThrownBy(() -> x.promote(UUID.randomUUID(), now)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void keepsTheOriginalRequestForPromotion() {
        WaitlistEntry e = entry();
        assertThat(e.toAllocationRequest()).isEqualTo(new AllocationRequest(2, List.of("1A"), null, null, null));
        assertThat(e.isExpired(now.plusSeconds(3600))).isTrue();
        assertThat(e.isExpired(now.plusSeconds(3599))).isFalse();
    }
}
