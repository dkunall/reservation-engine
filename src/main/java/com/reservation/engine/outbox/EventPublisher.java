package com.reservation.engine.outbox;

/** EXTENSION CONTRACT #4 - where outbox events go (Kafka, SNS, webhooks...). Must tolerate redelivery. */
public interface EventPublisher {
    void publish(OutboxEvent event);
}
