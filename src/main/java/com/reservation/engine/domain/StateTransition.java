package com.reservation.engine.domain;

import java.time.Instant;

/** A state change recorded by the aggregate; turned into audit rows + outbox events by the application layer. */
public record StateTransition(ReservationStatus from, ReservationStatus to, String actor, String reason, Instant at) {
}
