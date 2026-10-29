package com.reservation.engine.domain;

import com.reservation.engine.domain.exception.HoldExpiredException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reservation aggregate root. All status changes go through {@link ReservationStateMachine}
 * and are recorded as {@link StateTransition}s for audit/outbox.
 * Optimistic locking (@Version) is a backstop; hot paths additionally use row locks / conditional updates.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;

    @Column(name = "inventory_model", nullable = false, length = 30)
    private String inventoryModel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "slot_id")
    private UUID slotId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unit_codes", nullable = false, columnDefinition = "jsonb")
    private List<String> unitCodes = new ArrayList<>();

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "itinerary_id")
    private UUID itineraryId;

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

    protected Reservation() {
    }

    public static Reservation hold(String userId, Resource resource, int quantity, Instant startsAt, Instant endsAt,
                                   UUID slotId, Money total, Instant holdExpiresAt, Instant now) {
        Reservation r = new Reservation();
        r.id = UUID.randomUUID();
        r.userId = userId;
        r.resource = resource;
        r.resourceType = resource.getTypeCode();
        r.inventoryModel = resource.getInventoryModel();
        r.quantity = quantity;
        r.startsAt = startsAt;
        r.endsAt = endsAt;
        r.slotId = slotId;
        r.totalAmount = total.amount();
        r.currency = total.currency();
        r.holdExpiresAt = holdExpiresAt;
        r.createdAt = now;
        r.updatedAt = now;
        r.status = ReservationStatus.HELD;
        r.pendingTransitions.add(new StateTransition(null, ReservationStatus.HELD, userId, "hold created", now));
        return r;
    }

    /** Marks this reservation as an item of an itinerary (set once, before it is persisted). */
    public void attachToItinerary(UUID itineraryId) {
        if (this.itineraryId != null && !this.itineraryId.equals(itineraryId)) {
            throw new IllegalStateException("Reservation " + id + " already belongs to itinerary " + this.itineraryId);
        }
        this.itineraryId = itineraryId;
    }

    /** Records what the inventory strategy actually allocated (e.g. auto-assigned unit codes). */
    public void applyAllocation(AllocationResult allocation) {
        this.quantity = allocation.quantity();
        this.unitCodes = new ArrayList<>(allocation.unitCodes());
        if (allocation.slotId() != null) {
            this.slotId = allocation.slotId();
        }
        if (allocation.startsAt() != null) {
            this.startsAt = allocation.startsAt();
        }
        if (allocation.endsAt() != null) {
            this.endsAt = allocation.endsAt();
        }
    }

    public boolean isHoldExpired(Instant now) {
        return !holdExpiresAt.isAfter(now);
    }

    public boolean isOwnedBy(String user) {
        return userId.equals(user);
    }

    public void startPayment(String actor, Instant now) {
        if (isHoldExpired(now)) {
            throw new HoldExpiredException("Hold for reservation " + id + " expired at " + holdExpiresAt);
        }
        transition(ReservationStatus.PAYMENT_PENDING, actor, "payment started", now);
    }

    public void confirm(String actor, Instant now) {
        if (status == ReservationStatus.HELD && isHoldExpired(now)) {
            throw new HoldExpiredException("Hold for reservation " + id + " expired at " + holdExpiresAt);
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

    /** Returns and clears transitions recorded since the last call. */
    public List<StateTransition> pullTransitions() {
        List<StateTransition> copy = List.copyOf(pendingTransitions);
        pendingTransitions.clear();
        return copy;
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public Resource getResource() { return resource; }
    public UUID getResourceId() { return resource.getId(); }
    public String getResourceType() { return resourceType; }
    public String getInventoryModel() { return inventoryModel; }
    public ReservationStatus getStatus() { return status; }
    public int getQuantity() { return quantity; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public UUID getSlotId() { return slotId; }
    public List<String> getUnitCodes() { return List.copyOf(unitCodes); }
    public Money getTotal() { return new Money(totalAmount, currency); }
    public UUID getItineraryId() { return itineraryId; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getExpiredAt() { return expiredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
