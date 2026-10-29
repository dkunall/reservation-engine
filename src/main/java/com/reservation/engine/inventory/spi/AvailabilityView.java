package com.reservation.engine.inventory.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Model-agnostic availability snapshot (non-locking read; may be stale by the time a hold is attempted). */
public record AvailabilityView(UUID resourceId,
                               String inventoryModel,
                               boolean available,
                               Integer capacity,
                               Integer availableQuantity,
                               List<UnitAvailability> units,
                               List<SlotAvailability> slots) {

    public record UnitAvailability(String unitCode, boolean available) {
    }

    public record SlotAvailability(UUID slotId, Instant startsAt, Instant endsAt, int capacity, int available) {
    }
}
