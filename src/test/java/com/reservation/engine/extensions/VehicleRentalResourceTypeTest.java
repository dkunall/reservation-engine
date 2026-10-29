package com.reservation.engine.extensions;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.extensions.vehicle.VehicleRentalResourceType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VehicleRentalResourceTypeTest {

    private final VehicleRentalResourceType type = new VehicleRentalResourceType();
    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void declaresModelTtlAndBilling() {
        assertThat(type.code()).isEqualTo("VEHICLE");
        assertThat(type.inventoryModel()).isEqualTo("TIME_RANGE");
        assertThat(type.billingPeriod()).isEqualTo(Duration.ofHours(1));
        assertThat(type.holdTtl()).contains(Duration.ofMinutes(20));
    }

    @Test
    void validatesAttributesAndRentalLength() {
        assertThatThrownBy(() -> type.validateAttributes(Map.of("make", "VW"))).isInstanceOf(InvalidRequestException.class);
        type.validateAttributes(Map.of("make", "VW", "model", "Golf", "seats", 5));
        Instant s = now.plus(Duration.ofDays(1));
        assertThatThrownBy(() -> type.validateRequest(new AllocationRequest(1, List.of(), null, s, s.plusSeconds(1800))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Minimum");
        assertThatThrownBy(() -> type.validateRequest(new AllocationRequest(1, List.of(), null, s, s.plus(Duration.ofDays(31)))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Maximum");
        type.validateRequest(new AllocationRequest(1, List.of(), null, s, s.plus(Duration.ofHours(3))));
    }

    @Test
    void cancellationPolicyTiers() {
        Resource car = new Resource("VEHICLE", "TIME_RANGE", "Golf", null, Map.of(), Money.of("10", "EUR"), now);
        Instant pickup = now.plus(Duration.ofDays(3));
        Reservation r = Reservation.hold("u", car, 1, pickup, pickup.plus(Duration.ofHours(10)), null,
                Money.of("100", "EUR"), now.plusSeconds(600), now);
        r.confirm("u", now);
        var policy = type.cancellationPolicy().orElseThrow();
        assertThat(policy.evaluate(r, now).refund().amount()).isEqualByComparingTo("100.00");
        assertThat(policy.evaluate(r, pickup.minus(Duration.ofHours(2))).refund().amount()).isEqualByComparingTo("50.00");
        assertThat(policy.evaluate(r, pickup.plusSeconds(1)).allowed()).isFalse();
    }
}
