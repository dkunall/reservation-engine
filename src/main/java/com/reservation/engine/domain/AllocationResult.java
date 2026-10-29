package com.reservation.engine.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What a strategy actually allocated (e.g. auto-assigned units, resolved slot period). */
public record AllocationResult(int quantity, List<String> unitCodes, UUID slotId, Instant startsAt, Instant endsAt) {
    public AllocationResult {
        unitCodes = unitCodes == null ? List.of() : List.copyOf(unitCodes);
    }

    public static AllocationResult ofQuantity(int quantity) {
        return new AllocationResult(quantity, List.of(), null, null, null);
    }
}
