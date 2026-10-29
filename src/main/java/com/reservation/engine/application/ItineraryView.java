package com.reservation.engine.application;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ItineraryView(UUID id,
                            String userId,
                            ReservationStatus status,
                            int itemCount,
                            Money total,
                            Instant holdExpiresAt,
                            Instant confirmedAt,
                            Instant cancelledAt,
                            Instant expiredAt,
                            Instant createdAt,
                            List<ReservationView> items) {

    public static ItineraryView from(Itinerary it, List<Reservation> items) {
        return new ItineraryView(it.getId(), it.getUserId(), it.getStatus(), it.getItemCount(), it.getTotal(),
                it.getHoldExpiresAt(), it.getConfirmedAt(), it.getCancelledAt(), it.getExpiredAt(), it.getCreatedAt(),
                items == null ? null : items.stream().map(ReservationView::from).toList());
    }
}
