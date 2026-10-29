package com.reservation.engine.application;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.StateTransition;
import com.reservation.engine.outbox.OutboxWriter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records itinerary-level transitions as outbox events (aggregate "Itinerary") and delegates item transitions to
 * {@link ReservationEventRecorder} (audit + per-reservation events). Same transaction as the state change.
 */
@Component
public class ItineraryEventRecorder {
    private final ReservationEventRecorder reservations;
    private final OutboxWriter outbox;

    public ItineraryEventRecorder(ReservationEventRecorder reservations, OutboxWriter outbox) {
        this.reservations = reservations;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Itinerary it, List<Reservation> items) {
        items.forEach(reservations::record);
        for (StateTransition t : it.pullTransitions()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("itineraryId", it.getId().toString());
            payload.put("userId", it.getUserId());
            payload.put("fromStatus", t.from() == null ? null : t.from().name());
            payload.put("status", t.to().name());
            payload.put("reservationIds", items.stream().map(r -> r.getId().toString()).toList());
            payload.put("totalAmount", it.getTotal().amount().toPlainString());
            payload.put("currency", it.getTotal().currency());
            payload.put("actor", t.actor());
            payload.put("reason", t.reason());
            payload.put("occurredAt", t.at().toString());
            outbox.append("Itinerary", it.getId(), eventType(t.to()), payload);
        }
    }

    static String eventType(ReservationStatus to) {
        return switch (to) {
            case HELD -> "ItineraryHeld";
            case PAYMENT_PENDING -> "ItineraryPaymentPending";
            case CONFIRMED -> "ItineraryConfirmed";
            case CANCELLED -> "ItineraryCancelled";
            case EXPIRED -> "ItineraryExpired";
        };
    }
}
