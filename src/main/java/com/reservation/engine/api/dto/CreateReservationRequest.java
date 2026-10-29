package com.reservation.engine.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One request shape for every resource type: CAPACITY uses quantity; EXCLUSIVE_UNIT uses unitCodes or quantity;
 * TIME_SLOT uses slotId (or startsAt); TIME_RANGE uses startsAt/endsAt (+ optional unitCodes).
 */
public record CreateReservationRequest(
        @NotNull UUID resourceId,
        @Min(1) @Max(100) Integer quantity,
        @Size(max = 100) List<@NotBlank @Size(max = 50) String> unitCodes,
        UUID slotId,
        Instant startsAt,
        Instant endsAt) {
}
