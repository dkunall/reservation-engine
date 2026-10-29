package com.reservation.engine.observability;

import com.reservation.engine.outbox.OutboxEvent;
import com.reservation.engine.outbox.OutboxRepository;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports outbox backlog and permanently failed events as details (stays UP: a stuck broker should not fail liveness). */
@Component("outbox")
public class OutboxHealthIndicator implements HealthIndicator {
    private final OutboxRepository repository;

    public OutboxHealthIndicator(OutboxRepository repository) {
        this.repository = repository;
    }

    @Override
    public Health health() {
        long pending = repository.countByStatus(OutboxEvent.Status.PENDING);
        long failed = repository.countByStatus(OutboxEvent.Status.FAILED);
        return Health.up().withDetail("attention", failed > 0).withDetail("pending", pending).withDetail("failed", failed).build();
    }
}
