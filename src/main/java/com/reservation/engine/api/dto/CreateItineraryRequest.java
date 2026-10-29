package com.reservation.engine.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Each item uses exactly the single-reservation request shape. */
public record CreateItineraryRequest(@NotEmpty @Size(max = 20) List<@Valid @NotNull CreateReservationRequest> items) {
}
