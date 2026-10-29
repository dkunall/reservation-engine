package com.reservation.engine.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Same booking shape as a reservation request, plus an optional maximum wait (minutes). */
public record JoinWaitlistRequest(
        @NotNull UUID resourceId,
        @Min(1) @Max(100) Integer quantity,
        @Size(max = 100) List<@NotBlank @Size(max = 50) String> unitCodes,
        UUID slotId,
        Instant startsAt,
        Instant endsAt,
        @Min(1) @Max(43_200) Integer maxWaitMinutes) {
}
