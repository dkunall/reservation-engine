package com.reservation.engine.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record SlotRequest(@NotNull Instant startsAt, @NotNull Instant endsAt, @Min(1) @Max(100000) int capacity) {
}
