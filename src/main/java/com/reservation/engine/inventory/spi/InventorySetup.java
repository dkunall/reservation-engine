package com.reservation.engine.inventory.spi;

import java.time.Instant;
import java.util.List;

/**
 * Generic inventory provisioning input; each strategy uses (and validates) the fields relevant to it.
 *
 * @param buckets optional, CAPACITY only: split the counter into N bucket rows (hot-inventory / flash-sale mode).
 *                Fixed at first provisioning; null = keep the current layout (single row by default).
 */
public record InventorySetup(Integer capacity, List<String> unitCodes, List<SlotSpec> slots, Integer buckets) {
    public InventorySetup {
        unitCodes = unitCodes == null ? List.of() : List.copyOf(unitCodes);
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    /** Backward-compatible constructor (no bucket layout). */
    public InventorySetup(Integer capacity, List<String> unitCodes, List<SlotSpec> slots) {
        this(capacity, unitCodes, slots, null);
    }

    public record SlotSpec(Instant startsAt, Instant endsAt, int capacity) {
    }
}
