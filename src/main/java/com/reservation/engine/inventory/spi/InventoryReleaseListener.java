package com.reservation.engine.inventory.spi;

import java.time.Instant;
import java.util.UUID;

/**
 * EXTENSION CONTRACT - notified (synchronously, inside the releasing transaction) whenever inventory held by a
 * reservation is given back (cancel, expiry, declined-after-expiry). Implementations must only do transactional
 * database work (e.g. write a signal row) so the notification commits or rolls back with the release itself.
 * Used by the waitlist; could equally drive cache invalidation or "back in stock" alerts.
 */
public interface InventoryReleaseListener {

    void onRelease(UUID resourceId, UUID reservationId, String inventoryModel, Instant at);
}
