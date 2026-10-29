package com.reservation.engine.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Model-agnostic description of what to allocate. Each inventory strategy uses the fields it needs
 * and rejects requests missing required ones.
 */
public record AllocationRequest(int quantity, List<String> unitCodes, UUID slotId, Instant startsAt, Instant endsAt) {
    public AllocationRequest {
        unitCodes = unitCodes == null ? List.of() : List.copyOf(unitCodes);
    }

    public boolean hasUnitCodes() {
        return !unitCodes.isEmpty();
    }
}
