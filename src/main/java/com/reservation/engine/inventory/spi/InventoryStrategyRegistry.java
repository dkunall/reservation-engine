package com.reservation.engine.inventory.spi;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Resolves the strategy for an inventory model code. No switch chains: strategies self-register by {@code model()}. */
public class InventoryStrategyRegistry {
    private final Map<String, InventoryStrategy> strategies;

    public InventoryStrategyRegistry(List<InventoryStrategy> list) {
        Map<String, InventoryStrategy> m = new TreeMap<>();
        for (InventoryStrategy s : list) {
            InventoryStrategy prev = m.putIfAbsent(s.model(), s);
            if (prev != null) {
                throw new IllegalStateException("Duplicate inventory strategy for model " + s.model()
                        + ": " + prev.getClass().getName() + " and " + s.getClass().getName());
            }
        }
        this.strategies = Collections.unmodifiableMap(m);
    }

    public InventoryStrategy get(String model) {
        InventoryStrategy s = model == null ? null : strategies.get(model);
        if (s == null) {
            throw new IllegalStateException("No inventory strategy registered for model " + model);
        }
        return s;
    }

    public boolean supports(String model) {
        return model != null && strategies.containsKey(model);
    }

    public Set<String> models() {
        return strategies.keySet();
    }
}
