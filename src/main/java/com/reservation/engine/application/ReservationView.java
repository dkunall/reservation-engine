package com.reservation.engine.application;

import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read model returned by use cases (built inside the transaction, safe to serialise afterwards). */
public record ReservationView(UUID id,
                              String userId,
                              UUID resourceId,
                              String resourceName,
                              String resourceType,
                              String inventoryModel,
                              ReservationStatus status,
                              int quantity,
                              List<String> unitCodes,
                              UUID slotId,
                              Instant startsAt,
                              Instant endsAt,
                              Money total,
                              Instant holdExpiresAt,
                              Instant confirmedAt,
                              Instant cancelledAt,
                              Instant expiredAt,
                              Instant createdAt,
                              UUID itineraryId) {

    public static ReservationView from(Reservation r) {
        return new ReservationView(r.getId(), r.getUserId(), r.getResourceId(), r.getResource().getName(),
                r.getResourceType(), r.getInventoryModel(), r.getStatus(), r.getQuantity(), r.getUnitCodes(),
                r.getSlotId(), r.getStartsAt(), r.getEndsAt(), r.getTotal(), r.getHoldExpiresAt(),
                r.getConfirmedAt(), r.getCancelledAt(), r.getExpiredAt(), r.getCreatedAt(), r.getItineraryId());
    }
}
