package com.reservation.engine.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Generic inventory provisioning payload; which fields are required depends on the inventory model.
 * {@code buckets} (CAPACITY only, optional) enables sharded counters for hot inventory.
 */
public record InventoryRequest(
        @PositiveOrZero @Max(10_000_000) Integer capacity,
        @Size(max = 2000) List<@NotBlank @Size(max = 50) String> unitCodes,
        @Size(max = 2000) List<@Valid SlotRequest> slots,
        @Min(1) @Max(1024) Integer buckets) {

    /** Backward-compatible constructor (no bucket layout). */
    public InventoryRequest(Integer capacity, List<String> unitCodes, List<SlotRequest> slots) {
        this(capacity, unitCodes, slots, null);
    }
}
