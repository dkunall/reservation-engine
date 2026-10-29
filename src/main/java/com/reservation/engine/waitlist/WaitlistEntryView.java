package com.reservation.engine.waitlist;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WaitlistEntryView(UUID id,
                                String userId,
                                UUID resourceId,
                                WaitlistStatus status,
                                int quantity,
                                List<String> unitCodes,
                                UUID slotId,
                                Instant startsAt,
                                Instant endsAt,
                                Instant requestedAt,
                                Instant expiresAt,
                                UUID promotedReservationId,
                                Instant promotedAt,
                                String lastError) {

    public static WaitlistEntryView from(WaitlistEntry e) {
        return new WaitlistEntryView(e.getId(), e.getUserId(), e.getResourceId(), e.getStatus(), e.getQuantity(),
                e.getUnitCodes(), e.getSlotId(), e.getStartsAt(), e.getEndsAt(), e.getRequestedAt(), e.getExpiresAt(),
                e.getPromotedReservationId(), e.getPromotedAt(), e.getLastError());
    }
}
