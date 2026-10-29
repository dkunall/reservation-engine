package com.reservation.engine.extensions.vehicle;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.policy.CancellationPolicy;
import com.reservation.engine.inventory.spi.InventoryModels;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * EXTENSIBILITY DEMO: vehicle rental added purely as an extension (no core class edited).
 * <ul>
 *   <li>reuses the TIME_RANGE inventory model (one unit per vehicle, identified by plate)</li>
 *   <li>hourly billing, 1h minimum / 30 days maximum rental</li>
 *   <li>custom hold TTL and cancellation policy</li>
 *   <li>validates required resource attributes</li>
 * </ul>
 */
@Component
public class VehicleRentalResourceType implements ResourceTypeDefinition {

    public static final String CODE = "VEHICLE";
    static final Duration MIN_RENTAL = Duration.ofHours(1);
    static final Duration MAX_RENTAL = Duration.ofDays(30);

    private final VehicleCancellationPolicy cancellationPolicy = new VehicleCancellationPolicy();

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String inventoryModel() {
        return InventoryModels.TIME_RANGE;
    }

    @Override
    public Optional<Duration> holdTtl() {
        return Optional.of(Duration.ofMinutes(20));
    }

    @Override
    public Duration billingPeriod() {
        return Duration.ofHours(1);
    }

    @Override
    public Optional<CancellationPolicy> cancellationPolicy() {
        return Optional.of(cancellationPolicy);
    }

    @Override
    public void validateAttributes(Map<String, Object> attributes) {
        for (String required : new String[]{"make", "model", "seats"}) {
            if (!attributes.containsKey(required)) {
                throw new InvalidRequestException("VEHICLE resources require attribute '" + required + "'");
            }
        }
    }

    @Override
    public void validateRequest(AllocationRequest request) {
        Duration d = Duration.between(request.startsAt(), request.endsAt());
        if (d.compareTo(MIN_RENTAL) < 0) {
            throw new InvalidRequestException("Minimum rental is " + MIN_RENTAL.toHours() + " hour(s)");
        }
        if (d.compareTo(MAX_RENTAL) > 0) {
            throw new InvalidRequestException("Maximum rental is " + MAX_RENTAL.toDays() + " days");
        }
    }
}
