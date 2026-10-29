package com.reservation.engine.application;

import com.reservation.engine.domain.Reservation;
import com.reservation.engine.inventory.spi.InventoryReleaseListener;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Releases inventory for a reservation via the strategy of its (snapshotted) inventory model, then notifies
 * {@link InventoryReleaseListener}s in the same transaction.
 */
@Component
public class InventoryAllocator {
    private final InventoryStrategyRegistry strategies;
    private final List<InventoryReleaseListener> listeners;

    public InventoryAllocator(InventoryStrategyRegistry strategies) {
        this(strategies, List.of());
    }

    @Autowired
    public InventoryAllocator(InventoryStrategyRegistry strategies, List<InventoryReleaseListener> listeners) {
        this.strategies = strategies;
        this.listeners = List.copyOf(listeners);
    }

    public void release(Reservation reservation, Instant now) {
        strategies.get(reservation.getInventoryModel()).release(reservation.getId(), now);
        for (InventoryReleaseListener l : listeners) {
            l.onRelease(reservation.getResourceId(), reservation.getId(), reservation.getInventoryModel(), now);
        }
    }
}
