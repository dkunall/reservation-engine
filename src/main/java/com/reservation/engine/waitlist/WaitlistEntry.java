package com.reservation.engine.waitlist;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A user's place in the queue for a resource; WAITING -> PROMOTED | EXPIRED | CANCELLED (all terminal). */
@Entity
@Table(name = "waitlist_entry")
public class WaitlistEntry {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private WaitlistStatus status;

    @Column(nullable = false)
    private int quantity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unit_codes", nullable = false, columnDefinition = "jsonb")
    private List<String> unitCodes = new ArrayList<>();

    @Column(name = "slot_id")
    private UUID slotId;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "promoted_reservation_id")
    private UUID promotedReservationId;

    @Column(name = "promoted_at")
    private Instant promotedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected WaitlistEntry() {
    }

    public static WaitlistEntry join(String userId, UUID resourceId, AllocationRequest request, Instant expiresAt,
                                     Instant now) {
        WaitlistEntry e = new WaitlistEntry();
        e.id = UUID.randomUUID();
        e.userId = userId;
        e.resourceId = resourceId;
        e.status = WaitlistStatus.WAITING;
        e.quantity = request.quantity();
        e.unitCodes = new ArrayList<>(request.unitCodes());
        e.slotId = request.slotId();
        e.startsAt = request.startsAt();
        e.endsAt = request.endsAt();
        e.requestedAt = now;
        e.expiresAt = expiresAt;
        e.createdAt = now;
        e.updatedAt = now;
        return e;
    }

    public AllocationRequest toAllocationRequest() {
        return new AllocationRequest(quantity, unitCodes, slotId, startsAt, endsAt);
    }

    public void promote(UUID reservationId, Instant now) {
        requireWaiting(WaitlistStatus.PROMOTED);
        this.status = WaitlistStatus.PROMOTED;
        this.promotedReservationId = reservationId;
        this.promotedAt = now;
        this.attempts++;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        requireWaiting(WaitlistStatus.CANCELLED);
        this.status = WaitlistStatus.CANCELLED;
        this.updatedAt = now;
    }

    public void expire(String reason, Instant now) {
        requireWaiting(WaitlistStatus.EXPIRED);
        this.status = WaitlistStatus.EXPIRED;
        this.lastError = reason == null ? null : (reason.length() > 255 ? reason.substring(0, 255) : reason);
        this.updatedAt = now;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isOwnedBy(String user) {
        return userId.equals(user);
    }

    private void requireWaiting(WaitlistStatus to) {
        if (status != WaitlistStatus.WAITING) {
            throw new InvalidStateTransitionException("Waitlist entry " + id + " is " + status + "; cannot become " + to);
        }
    }

    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public UUID getResourceId() { return resourceId; }
    public WaitlistStatus getStatus() { return status; }
    public int getQuantity() { return quantity; }
    public List<String> getUnitCodes() { return List.copyOf(unitCodes); }
    public UUID getSlotId() { return slotId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public UUID getPromotedReservationId() { return promotedReservationId; }
    public Instant getPromotedAt() { return promotedAt; }
    public String getLastError() { return lastError; }
}
