package com.reservation.engine.resourcetype;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.policy.CancellationPolicy;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * EXTENSION CONTRACT #1 - a bookable resource type (FLIGHT, HOTEL_ROOM, VEHICLE, ...).
 * <p>
 * Register a Spring bean implementing this (or declare it in {@code reservation.resource-types.custom})
 * to add a new type. The core workflow never branches on the type code; it only asks the definition
 * for its inventory model, policies and validation hooks.
 */
public interface ResourceTypeDefinition {

    /** Upper-case code, e.g. "HOTEL_ROOM". */
    String code();

    /** Code of the {@link com.reservation.engine.inventory.spi.InventoryStrategy} that manages its inventory. */
    String inventoryModel();

    /** Hold TTL override; empty -> engine default. */
    default Optional<Duration> holdTtl() {
        return Optional.empty();
    }

    /** Billing period for time-range pricing (price is per period, rounded up). */
    default Duration billingPeriod() {
        return Duration.ofDays(1);
    }

    /** Type-specific cancellation rules; empty -> engine default. */
    default Optional<CancellationPolicy> cancellationPolicy() {
        return Optional.empty();
    }

    /** Validate resource attributes at creation time; throw InvalidRequestException on error. */
    default void validateAttributes(Map<String, Object> attributes) {
    }

    /** Validate a booking request beyond the inventory model's generic checks. */
    default void validateRequest(AllocationRequest request) {
    }
}
