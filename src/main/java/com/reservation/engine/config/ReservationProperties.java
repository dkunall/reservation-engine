package com.reservation.engine.config;

import com.reservation.engine.payment.FakePaymentAdapter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** All engine settings under the {@code reservation.*} prefix (overridable via env, e.g. RESERVATION_HOLD_DEFAULTTTL). */
@ConfigurationProperties(prefix = "reservation")
public record ReservationProperties(Hold hold,
                                    Cancellation cancellation,
                                    Expiry expiry,
                                    Payment payment,
                                    Idempotency idempotency,
                                    Outbox outbox,
                                    Jwt jwt,
                                    List<CustomResourceType> customResourceTypes) {

    public ReservationProperties {
        hold = hold != null ? hold : new Hold(null, null);
        cancellation = cancellation != null ? cancellation : new Cancellation(null);
        expiry = expiry != null ? expiry : new Expiry(0);
        payment = payment != null ? payment : new Payment(null, null, null);
        idempotency = idempotency != null ? idempotency : new Idempotency(null, null);
        outbox = outbox != null ? outbox : new Outbox(0);
        jwt = jwt != null ? jwt : new Jwt(null, null);
        customResourceTypes = customResourceTypes != null ? customResourceTypes : List.of();
    }

    public record Hold(Duration defaultTtl, Duration maxTtl) {
        public Hold {
            defaultTtl = defaultTtl != null ? defaultTtl : Duration.ofMinutes(10);
            maxTtl = maxTtl != null ? maxTtl : Duration.ofHours(1);
        }
    }

    public record Cancellation(Duration cutoffBeforeStart) {
        public Cancellation {
            cutoffBeforeStart = cutoffBeforeStart != null ? cutoffBeforeStart : Duration.ofHours(1);
        }
    }

    public record Expiry(int batchSize) {
        public Expiry {
            batchSize = batchSize > 0 ? batchSize : 100;
        }
    }

    public record Payment(Duration reconcileAfter, FakePaymentAdapter.Mode fakeMode, Duration fakeLatency) {
        public Payment {
            reconcileAfter = reconcileAfter != null ? reconcileAfter : Duration.ofMinutes(1);
            fakeMode = fakeMode != null ? fakeMode : FakePaymentAdapter.Mode.APPROVE;
            fakeLatency = fakeLatency != null ? fakeLatency : Duration.ZERO;
        }
    }

    public record Idempotency(Duration ttl, Duration inProgressTimeout) {
        public Idempotency {
            ttl = ttl != null ? ttl : Duration.ofHours(24);
            inProgressTimeout = inProgressTimeout != null ? inProgressTimeout : Duration.ofMinutes(2);
        }
    }

    public record Outbox(int batchSize) {
        public Outbox {
            batchSize = batchSize > 0 ? batchSize : 100;
        }
    }

    /** HMAC secret (>= 32 bytes) for HS256 JWTs; supply via env JWT_SECRET. Optional issuer check. */
    public record Jwt(String secret, String issuer) {
    }

    /** Config-only resource types (no code): code + inventory model (+ optional TTL / billing period). */
    public record CustomResourceType(String code, String inventoryModel, Duration holdTtl, Duration billingPeriod) {
    }
}
