package com.reservation.engine.domain.policy;

import com.reservation.engine.domain.Money;

public record CancellationDecision(boolean allowed, Money refund, String reason) {
    public static CancellationDecision allow(Money refund, String reason) {
        return new CancellationDecision(true, refund, reason);
    }

    public static CancellationDecision deny(String reason) {
        return new CancellationDecision(false, null, reason);
    }
}
