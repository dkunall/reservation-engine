package com.reservation.engine.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Business metrics: holds, confirms, conflicts, expiries, cancellations (+ latency timers). */
@Component
public class ReservationMetrics {
    private final MeterRegistry registry;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void holdCreated(String type, String model) {
        Counter.builder("reservation.holds").tag("result", "created").tag("type", type).tag("model", model)
                .register(registry).increment();
    }

    public void conflict(String type, String model, String operation) {
        Counter.builder("reservation.conflicts").tag("type", type).tag("model", model).tag("operation", operation)
                .register(registry).increment();
    }

    public void confirmOutcome(String type, String outcome) {
        Counter.builder("reservation.confirms").tag("type", type).tag("outcome", outcome)
                .register(registry).increment();
    }

    public void expired(String type, int count) {
        Counter.builder("reservation.expiries").tag("type", type).register(registry).increment(count);
    }

    public void cancelled(String type) {
        Counter.builder("reservation.cancellations").tag("type", type).register(registry).increment();
    }

    public void idempotentReplay(String operation) {
        Counter.builder("reservation.idempotency.replays").tag("operation", operation).register(registry).increment();
    }

    public void itineraryHold(String result) {
        Counter.builder("itinerary.holds").tag("result", result).register(registry).increment();
    }

    public void itineraryConfirm(String outcome) {
        Counter.builder("itinerary.confirms").tag("outcome", outcome).register(registry).increment();
    }

    public void itineraryExpired(int count) {
        Counter.builder("itinerary.expiries").register(registry).increment(count);
    }

    public void waitlist(String event) {
        Counter.builder("waitlist.events").tag("event", event).register(registry).increment();
    }

    public <T> T time(String name, Supplier<T> action) {
        return Timer.builder(name).publishPercentileHistogram().register(registry).record(action);
    }
}
