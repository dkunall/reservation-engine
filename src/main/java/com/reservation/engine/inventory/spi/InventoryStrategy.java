package com.reservation.engine.inventory.spi;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.AllocationResult;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;

import java.time.Instant;
import java.util.UUID;

/**
 * EXTENSION CONTRACT #2 - an inventory model. Owns its schema and its concurrency-control technique.
 * <p>
 * All mutating methods are invoked INSIDE the caller's (short) database transaction and must not commit;
 * a thrown exception rolls back both the allocation and the reservation row.
 */
public interface InventoryStrategy {

    /** Model code, e.g. {@link InventoryModels#CAPACITY}. */
    String model();

    /** Provision (add) inventory for a resource. */
    void addInventory(Resource resource, InventorySetup setup);

    /** Reject requests that don't fit this model (throws InvalidRequestException). */
    void validateRequest(Resource resource, AllocationRequest request);

    /** Number of priced units for the request (quantity, nights x rooms, ...). */
    long billableUnits(AllocationRequest request, ResourceTypeDefinition type);

    /** Atomically allocate or throw InventoryUnavailableException. */
    AllocationResult allocate(Resource resource, UUID reservationId, AllocationRequest request);

    /** Release all active allocations of the reservation. Must be idempotent. */
    void release(UUID reservationId, Instant now);

    /** Non-locking availability read. */
    AvailabilityView availability(Resource resource, AvailabilityQuery query);

    /**
     * Relative position of this model's allocation in a multi-item (itinerary) transaction; items are allocated in
     * ascending rank, then resource id. Models whose allocation locks a SHARED hot row (counters) should return a
     * higher rank so that lock is taken last and held for the shortest time. Must be a constant per model
     * (it is part of the global lock order). Default 0.
     */
    default int lockOrderRank() {
        return 0;
    }
}
