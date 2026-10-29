package com.reservation.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code reservation.waitlist.*}
 *
 * @param defaultTtl         how long an entry waits if the client does not ask for less
 * @param maxTtl             upper bound for a requested entry lifetime
 * @param candidatesPerSignal max WAITING entries examined per processed release signal
 * @param signalsPerRun      max signals processed by one promoter run
 */
@ConfigurationProperties(prefix = "reservation.waitlist")
public record WaitlistProperties(Duration defaultTtl, Duration maxTtl, int candidatesPerSignal, int signalsPerRun) {
    public WaitlistProperties {
        defaultTtl = defaultTtl != null ? defaultTtl : Duration.ofHours(24);
        maxTtl = maxTtl != null ? maxTtl : Duration.ofDays(7);
        candidatesPerSignal = candidatesPerSignal > 0 ? candidatesPerSignal : 20;
        signalsPerRun = signalsPerRun > 0 ? signalsPerRun : 100;
    }
}
