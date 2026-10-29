package com.reservation.engine.domain;

import com.reservation.engine.domain.policy.CancellationDecision;
import com.reservation.engine.domain.policy.DefaultCancellationPolicy;
import com.reservation.engine.domain.policy.DefaultHoldTtlPolicy;
import com.reservation.engine.resourcetype.SimpleResourceTypeDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PoliciesTest {

    private final Instant now = Instant.parse("2030-01-01T10:00:00Z");

    @Test
    void holdTtlUsesTypeOverrideDefaultAndCap() {
        DefaultHoldTtlPolicy policy = new DefaultHoldTtlPolicy(Duration.ofMinutes(10), Duration.ofMinutes(30));
        assertThat(policy.holdTtl(new SimpleResourceTypeDefinition("A", "CAPACITY"))).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.holdTtl(new SimpleResourceTypeDefinition("B", "CAPACITY", Duration.ofMinutes(5), null)))
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.holdTtl(new SimpleResourceTypeDefinition("C", "CAPACITY", Duration.ofHours(5), null)))
                .isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void holdTtlRejectsInvalidConfig() {
        assertThatThrownBy(() -> new DefaultHoldTtlPolicy(Duration.ZERO, Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DefaultHoldTtlPolicy(Duration.ofMinutes(10), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Reservation confirmedStartingAt(Instant start) {
        Resource res = new Resource("HOTEL_ROOM", "TIME_RANGE", "Room", null, Map.of(), Money.of("80", "USD"), now);
        Reservation r = Reservation.hold("u", res, 1, start, start.plus(Duration.ofDays(1)), null,
                Money.of("80", "USD"), now.plusSeconds(600), now);
        r.confirm("u", now);
        return r;
    }

    @Test
    void defaultCancellationFullRefundBeforeCutoff() {
        DefaultCancellationPolicy p = new DefaultCancellationPolicy(Duration.ofHours(1));
        CancellationDecision d = p.evaluate(confirmedStartingAt(now.plus(Duration.ofDays(2))), now);
        assertThat(d.allowed()).isTrue();
        assertThat(d.refund().amount()).isEqualByComparingTo("80.00");
    }

    @Test
    void defaultCancellationDeniedInsideCutoff() {
        DefaultCancellationPolicy p = new DefaultCancellationPolicy(Duration.ofHours(1));
        CancellationDecision d = p.evaluate(confirmedStartingAt(now.plus(Duration.ofMinutes(30))), now);
        assertThat(d.allowed()).isFalse();
    }

    @Test
    void defaultCancellationOfHoldRefundsNothing() {
        Resource res = new Resource("BUS", "CAPACITY", "Bus", null, Map.of(), Money.of("5", "USD"), now);
        Reservation held = Reservation.hold("u", res, 1, null, null, null, Money.of("5", "USD"), now.plusSeconds(60), now);
        CancellationDecision d = new DefaultCancellationPolicy(Duration.ofHours(1)).evaluate(held, now);
        assertThat(d.allowed()).isTrue();
        assertThat(d.refund().isZero()).isTrue();
    }
}
