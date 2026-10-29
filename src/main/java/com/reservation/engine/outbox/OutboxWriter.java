package com.reservation.engine.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Appends events in the caller's transaction (MANDATORY) so state change and event commit atomically. */
@Component
public class OutboxWriter {
    private final OutboxRepository repository;
    private final Clock clock;

    public OutboxWriter(OutboxRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        repository.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload, clock.instant()));
    }
}
