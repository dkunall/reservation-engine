package com.reservation.engine.domain.policy;

import com.reservation.engine.resourcetype.ResourceTypeDefinition;

import java.time.Duration;

/** Uses the type's own TTL if it declares one, otherwise the configured default; always capped at max. */
public class DefaultHoldTtlPolicy implements HoldTtlPolicy {
    private final Duration defaultTtl;
    private final Duration maxTtl;

    public DefaultHoldTtlPolicy(Duration defaultTtl, Duration maxTtl) {
        if (defaultTtl.isNegative() || defaultTtl.isZero()) {
            throw new IllegalArgumentException("default hold TTL must be positive");
        }
        if (maxTtl.compareTo(defaultTtl) < 0) {
            throw new IllegalArgumentException("max hold TTL must be >= default");
        }
        this.defaultTtl = defaultTtl;
        this.maxTtl = maxTtl;
    }

    @Override
    public Duration holdTtl(ResourceTypeDefinition type) {
        Duration ttl = type.holdTtl().orElse(defaultTtl);
        if (ttl.isNegative() || ttl.isZero()) {
            return defaultTtl;
        }
        return ttl.compareTo(maxTtl) > 0 ? maxTtl : ttl;
    }
}
