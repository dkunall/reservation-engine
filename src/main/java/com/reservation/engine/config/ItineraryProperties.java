package com.reservation.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code reservation.itinerary.*}
 *
 * @param maxItems          maximum items per itinerary (DB allows up to 20)
 * @param lockRetryAttempts whole-transaction retries when the database reports a deadlock / lock timeout
 */
@ConfigurationProperties(prefix = "reservation.itinerary")
public record ItineraryProperties(int maxItems, int lockRetryAttempts) {
    public ItineraryProperties {
        maxItems = maxItems > 0 ? Math.min(maxItems, 20) : 10;
        lockRetryAttempts = lockRetryAttempts > 0 ? lockRetryAttempts : 3;
    }
}
