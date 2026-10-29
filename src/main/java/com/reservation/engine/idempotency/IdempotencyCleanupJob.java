package com.reservation.engine.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
public class IdempotencyCleanupJob {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);
    private final IdempotencyRepository repository;
    private final Clock clock;

    public IdempotencyCleanupJob(IdempotencyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${reservation.idempotency.cleanup-interval:PT1H}")
    public void cleanup() {
        int n = repository.deleteAllExpired(clock.instant());
        if (n > 0) {
            log.info("idempotency cleanup deleted={}", n);
        }
    }
}
