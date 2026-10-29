package com.reservation.engine.api.dto;

import jakarta.validation.constraints.Size;

public record ConfirmReservationRequest(@Size(max = 100) String paymentMethodToken) {
}
