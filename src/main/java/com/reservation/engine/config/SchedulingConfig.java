package com.reservation.engine.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (expiry sweeper, reconciliation, outbox, idempotency cleanup). Disabled in tests. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "reservation.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
