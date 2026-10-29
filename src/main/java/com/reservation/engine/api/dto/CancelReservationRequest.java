package com.reservation.engine.api.dto;

import jakarta.validation.constraints.Size;

public record CancelReservationRequest(@Size(max = 255) String reason) {
}
