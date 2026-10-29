package com.reservation.engine.inventory;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.inventory.range.TimeRangeInventoryStrategy;
import com.reservation.engine.resourcetype.SimpleResourceTypeDefinition;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure rules of the TIME_RANGE strategy (validation + billing); DB behaviour is covered by ITs. */
class TimeRangeRulesTest {

    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private final TimeRangeInventoryStrategy strategy =
            new TimeRangeInventoryStrategy(null, null, null, Clock.fixed(now, ZoneOffset.UTC));
    private final Resource room = new Resource("HOTEL_ROOM", "TIME_RANGE", "Room", null, Map.of(), Money.of("1", "USD"), now);

    private AllocationRequest req(int qty, Instant s, Instant e) {
        return new AllocationRequest(qty, List.of(), null, s, e);
    }

    @Test
    void billsPerStartedPeriodTimesUnits() {
        var daily = new SimpleResourceTypeDefinition("HOTEL_ROOM", "TIME_RANGE", null, Duration.ofDays(1));
        var hourly = new SimpleResourceTypeDefinition("VEHICLE", "TIME_RANGE", null, Duration.ofHours(1));
        Instant s = now.plus(Duration.ofDays(1));
        assertThat(strategy.billableUnits(req(1, s, s.plus(Duration.ofDays(3))), daily)).isEqualTo(3);
        assertThat(strategy.billableUnits(req(2, s, s.plus(Duration.ofDays(3))), daily)).isEqualTo(6);
        assertThat(strategy.billableUnits(req(1, s, s.plus(Duration.ofHours(25))), daily)).isEqualTo(2);
        assertThat(strategy.billableUnits(req(1, s, s.plus(Duration.ofMinutes(90))), hourly)).isEqualTo(2);
    }

    @Test
    void validatesRange() {
        Instant s = now.plus(Duration.ofDays(1));
        assertThatThrownBy(() -> strategy.validateRequest(room, req(1, null, s)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strategy.validateRequest(room, req(1, s, s)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strategy.validateRequest(room, req(1, now.minusSeconds(1), s)))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strategy.validateRequest(room, req(1, s, s.plus(Duration.ofDays(400)))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strategy.validateRequest(room,
                new AllocationRequest(1, List.of(), UUID.randomUUID(), s, s.plusSeconds(3600))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> strategy.validateRequest(room,
                new AllocationRequest(1, List.of("101", "102"), null, s, s.plusSeconds(3600))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("quantity");
        strategy.validateRequest(room, req(1, s, s.plus(Duration.ofDays(2))));
    }
}
