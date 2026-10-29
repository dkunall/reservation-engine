package com.reservation.engine.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory payment provider for local runs and tests. Behaviour is chosen per call by the payment method
 * token (tok_decline, tok_unknown, tok_error) or globally via {@link #setMode(Mode)}. Idempotent on key.
 * "UNKNOWN" simulates a timeout where the provider actually succeeded: {@link #status} later reports SUCCEEDED.
 */
public class FakePaymentAdapter implements PaymentPort {

    public enum Mode { APPROVE, DECLINE, UNKNOWN, ERROR }

    private static final Logger log = LoggerFactory.getLogger(FakePaymentAdapter.class);

    private final Map<String, PaymentResult> settled = new ConcurrentHashMap<>();
    private volatile Mode mode;
    private volatile Duration latency;

    public FakePaymentAdapter(Mode mode, Duration latency) {
        this.mode = mode;
        this.latency = latency;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setLatency(Duration latency) {
        this.latency = latency;
    }

    public void reset(Mode defaultMode) {
        this.mode = defaultMode;
        this.latency = Duration.ZERO;
        settled.clear();
    }

    @Override
    public PaymentResult charge(ChargeRequest request) {
        sleep();
        PaymentResult previous = settled.get(request.idempotencyKey());
        if (previous != null) {
            return previous;
        }
        Mode effective = modeFor(request.paymentMethodToken());
        log.info("fake payment charge key={} reservation={} amount={} {} mode={}", request.idempotencyKey(),
                request.reservationId(), request.amount().amount(), request.amount().currency(), effective);
        return switch (effective) {
            case APPROVE -> settled.computeIfAbsent(request.idempotencyKey(),
                    k -> new PaymentResult(Outcome.SUCCEEDED, "fake_ch_" + UUID.randomUUID(), "approved"));
            case DECLINE -> settled.computeIfAbsent(request.idempotencyKey(),
                    k -> new PaymentResult(Outcome.DECLINED, null, "card declined"));
            case UNKNOWN -> {
                settled.putIfAbsent(request.idempotencyKey(),
                        new PaymentResult(Outcome.SUCCEEDED, "fake_ch_" + UUID.randomUUID(), "approved (late)"));
                yield PaymentResult.unknown("provider timeout");
            }
            case ERROR -> throw new IllegalStateException("simulated provider failure");
        };
    }

    @Override
    public PaymentResult refund(RefundRequest request) {
        sleep();
        return settled.computeIfAbsent(request.idempotencyKey(),
                k -> new PaymentResult(Outcome.SUCCEEDED, "fake_rf_" + UUID.randomUUID(), "refunded"));
    }

    @Override
    public PaymentResult status(String idempotencyKey) {
        PaymentResult r = settled.get(idempotencyKey);
        return r != null ? r : PaymentResult.unknown("no record at provider");
    }

    private Mode modeFor(String token) {
        if (token == null) {
            return mode;
        }
        return switch (token) {
            case "tok_decline" -> Mode.DECLINE;
            case "tok_unknown" -> Mode.UNKNOWN;
            case "tok_error" -> Mode.ERROR;
            default -> mode;
        };
    }

    private void sleep() {
        Duration d = latency;
        if (d != null && !d.isZero()) {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
