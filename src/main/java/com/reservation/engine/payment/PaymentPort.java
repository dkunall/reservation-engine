package com.reservation.engine.payment;

import com.reservation.engine.domain.Money;

import java.util.UUID;

/**
 * EXTENSION CONTRACT #3 - payment provider adapter. Implementations MUST be idempotent on
 * {@code idempotencyKey}. Never called while a database transaction / row lock is held.
 */
public interface PaymentPort {

    PaymentResult charge(ChargeRequest request);

    PaymentResult refund(RefundRequest request);

    /** Look up the final outcome of a previous charge/refund (used by reconciliation). */
    PaymentResult status(String idempotencyKey);

    /** {@code reservationId} is the booking reference: a reservation id, or an itinerary id for itinerary payments. */
    record ChargeRequest(String idempotencyKey, UUID reservationId, Money amount, String paymentMethodToken) {
    }

    record RefundRequest(String idempotencyKey, UUID reservationId, Money amount) {
    }

    enum Outcome { SUCCEEDED, DECLINED, UNKNOWN }

    record PaymentResult(Outcome outcome, String providerReference, String message) {
        public static PaymentResult unknown(String message) {
            return new PaymentResult(Outcome.UNKNOWN, null, message);
        }
    }
}
