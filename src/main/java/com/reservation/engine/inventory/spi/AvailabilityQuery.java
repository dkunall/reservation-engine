package com.reservation.engine.inventory.spi;

import java.time.Instant;

public record AvailabilityQuery(Instant from, Instant to, int quantity) {
}
