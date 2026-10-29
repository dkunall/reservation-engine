package com.reservation.engine.domain;

import com.reservation.engine.domain.exception.HoldExpiredException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Groups several reservations (its items) that move through the SAME lifecycle together. It reuses
 * {@link ReservationStatus} and {@link ReservationStateMachine}: an itinerary is "a reservation of reservations".
 * Items are ordinary {@link Reservation}s carrying {@code itineraryId}; they share the itinerary's hold deadline.
 */
@Entity
@Table(name = "itinerary")
public class Itinerary {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @Transient
    private final List<StateTransition> pendingTransitions = new ArrayList<>();

    protected Itinerary() {
    }

    public static Itinerary hold(String userId, int itemCount, Money total, Instant holdExpiresAt, Instant now) {
        Itinerary it = new Itinerary();
        it.id = UUID.randomUUID();
        it.userId = userId;
        it.itemCount = itemCount;
        it.totalAmount = total.amount();
        it.currency = total.currency();
        it.holdExpiresAt = holdExpiresAt;
        it.createdAt = now;
        it.updatedAt = now;
        it.status = ReservationStatus.HELD;
        it.pendingTransitions.add(new StateTransition(null, ReservationStatus.HELD, userId, "itinerary held", now));
        return it;
    }

    public boolean isHoldExpired(Instant now) {
        return !holdExpiresAt.isAfter(now);
    }

    public boolean isOwnedBy(String user) {
        return userId.equals(user);
    }

    public void startPayment(String actor, Instant now) {
        if (isHoldExpired(now)) {
            throw new HoldExpiredException("Hold for itinerary " + id + " expired at " + holdExpiresAt);
        }
        transition(ReservationStatus.PAYMENT_PENDING, actor, "payment started", now);
    }

    public void confirm(String actor, Instant now) {
        if (status == ReservationStatus.HELD && isHoldExpired(now)) {
            throw new HoldExpiredException("Hold for itinerary " + id + " expired at " + holdExpiresAt);
        }
        transition(ReservationStatus.CONFIRMED, actor, "confirmed", now);
        this.confirmedAt = now;
    }

    public void revertToHeld(String actor, String reason, Instant now) {
        transition(ReservationStatus.HELD, actor, reason, now);
    }

    public void expire(String actor, String reason, Instant now) {
        transition(ReservationStatus.EXPIRED, actor, reason, now);
        this.expiredAt = now;
    }

    public void cancel(String actor, String reason, Instant now) {
        transition(ReservationStatus.CANCELLED, actor, reason, now);
        this.cancelledAt = now;
    }

    private void transition(ReservationStatus to, String actor, String reason, Instant now) {
        ReservationStateMachine.assertTransition(status, to);
        ReservationStatus from = status;
        this.status = to;
        this.updatedAt = now;
        pendingTransitions.add(new StateTransition(from, to, actor, reason, now));
    }

    public List<StateTransition> pullTransitions() {
        List<StateTransition> copy = List.copyOf(pendingTransitions);
        pendingTransitions.clear();
        return copy;
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public ReservationStatus getStatus() { return status; }
    public int getItemCount() { return itemCount; }
    public Money getTotal() { return new Money(totalAmount, currency); }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getExpiredAt() { return expiredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
