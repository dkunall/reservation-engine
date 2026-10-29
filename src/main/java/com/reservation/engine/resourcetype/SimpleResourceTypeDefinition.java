package com.reservation.engine.resourcetype;

import java.time.Duration;
import java.util.Optional;

/** Data-only resource type (no custom hooks) used for built-ins and config-declared types. */
public record SimpleResourceTypeDefinition(String code, String inventoryModel, Duration ttl, Duration period)
        implements ResourceTypeDefinition {

    public SimpleResourceTypeDefinition(String code, String inventoryModel) {
        this(code, inventoryModel, null, null);
    }

    @Override
    public Optional<Duration> holdTtl() {
        return Optional.ofNullable(ttl);
    }

    @Override
    public Duration billingPeriod() {
        return period == null ? Duration.ofDays(1) : period;
    }
}
