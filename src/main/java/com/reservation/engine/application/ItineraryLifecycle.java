package com.reservation.engine.application;

import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Applies one lifecycle step to an itinerary AND all of its items, reusing the per-reservation domain methods
 * (and therefore the same state machine). Callers hold the itinerary row lock and the item row locks
 * (itinerary first, then items in id order) and are inside a transaction; events are recorded by the caller.
 */
@Component
public class ItineraryLifecycle {
    private final InventoryAllocator inventory;

    public ItineraryLifecycle(InventoryAllocator inventory) {
        this.inventory = inventory;
    }

    public void startPayment(Itinerary it, List<Reservation> items, String actor, Instant now) {
        it.startPayment(actor, now);
        items.forEach(r -> r.startPayment(actor, now));
    }

    public void confirm(Itinerary it, List<Reservation> items, String actor, Instant now) {
        it.confirm(actor, now);
        items.forEach(r -> r.confirm(actor, now));
    }

    public void revertToHeld(Itinerary it, List<Reservation> items, String actor, String reason, Instant now) {
        it.revertToHeld(actor, reason, now);
        items.forEach(r -> r.revertToHeld(actor, reason, now));
    }

    public void expire(Itinerary it, List<Reservation> items, String actor, String reason, Instant now) {
        it.expire(actor, reason, now);
        for (Reservation r : items) {
            if (r.getStatus() != ReservationStatus.EXPIRED) {
                r.expire(actor, reason, now);
                inventory.release(r, now);
            }
        }
    }

    public void cancel(Itinerary it, List<Reservation> items, String actor, String reason, Instant now) {
        it.cancel(actor, reason, now);
        for (Reservation r : items) {
            r.cancel(actor, reason, now);
            inventory.release(r, now);
        }
    }
}
