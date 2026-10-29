package com.reservation.engine.payment;

import com.reservation.engine.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A charge or refund attempt; its id doubles as the idempotency key sent to the payment provider. */
@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    public enum Type { CHARGE, REFUND }

    public enum Status { PENDING, UNKNOWN, SUCCEEDED, DECLINED, FAILED;
        public boolean isUnresolved() {
            return this == PENDING || this == UNKNOWN;
        }
    }

    @Id
    private UUID id;

    /** Owner is exactly one of reservation / itinerary (DB CHECK num_nonnulls(...) = 1). */
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "itinerary_id")
    private UUID itineraryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(UUID reservationId, Type type, Money amount, Instant now) {
        this.id = UUID.randomUUID();
        this.reservationId = reservationId;
        this.type = type;
        this.status = Status.PENDING;
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A charge or refund for a whole itinerary (single payment for the total). */
    public static PaymentTransaction forItinerary(UUID itineraryId, Type type, Money amount, Instant now) {
        PaymentTransaction p = new PaymentTransaction(null, type, amount, now);
        p.itineraryId = itineraryId;
        return p;
    }

    public void resolve(Status newStatus, String providerReference, String failureReason, Instant now) {
        this.status = newStatus;
        this.providerReference = providerReference;
        this.failureReason = failureReason == null ? null
                : (failureReason.length() > 255 ? failureReason.substring(0, 255) : failureReason);
        this.attempts++;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getReservationId() { return reservationId; }
    public UUID getItineraryId() { return itineraryId; }
    /** Reservation id or itinerary id, whichever owns this payment. */
    public UUID getOwnerId() { return reservationId != null ? reservationId : itineraryId; }
    public boolean isItineraryPayment() { return itineraryId != null; }
    public Type getType() { return type; }
    public Status getStatus() { return status; }
    public Money getAmount() { return new Money(amount, currency); }
    public String getProviderReference() { return providerReference; }
    public String getFailureReason() { return failureReason; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
}
