package com.reservation.engine.inventory.spi;

/** Codes of the built-in inventory models. New models only need a new {@link InventoryStrategy} bean. */
public final class InventoryModels {
    public static final String CAPACITY = "CAPACITY";
    public static final String EXCLUSIVE_UNIT = "EXCLUSIVE_UNIT";
    public static final String TIME_SLOT = "TIME_SLOT";
    public static final String TIME_RANGE = "TIME_RANGE";

    private InventoryModels() {
    }
}
