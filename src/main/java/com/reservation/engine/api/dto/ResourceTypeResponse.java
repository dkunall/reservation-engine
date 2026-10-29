package com.reservation.engine.api.dto;

import java.time.Duration;

public record ResourceTypeResponse(String code, String inventoryModel, Duration holdTtl, Duration billingPeriod) {
}
