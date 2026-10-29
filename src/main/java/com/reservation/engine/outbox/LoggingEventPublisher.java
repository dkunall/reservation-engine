package com.reservation.engine.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default publisher: structured log line per event. Replace with a broker adapter in production. */
public class LoggingEventPublisher implements EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("outbox event published id={} type={} aggregateType={} aggregateId={} payload={}",
                event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(), event.getPayload());
    }
}
