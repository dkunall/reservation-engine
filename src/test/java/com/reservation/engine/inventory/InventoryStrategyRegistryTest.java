package com.reservation.engine.inventory;

import com.reservation.engine.inventory.spi.InventoryStrategy;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InventoryStrategyRegistryTest {

    private static InventoryStrategy strategy(String model) {
        InventoryStrategy s = mock(InventoryStrategy.class);
        when(s.model()).thenReturn(model);
        return s;
    }

    @Test
    void resolvesByModelCode() {
        InventoryStrategy capacity = strategy("CAPACITY");
        InventoryStrategy range = strategy("TIME_RANGE");
        InventoryStrategyRegistry registry = new InventoryStrategyRegistry(List.of(capacity, range));
        assertThat(registry.get("CAPACITY")).isSameAs(capacity);
        assertThat(registry.get("TIME_RANGE")).isSameAs(range);
        assertThat(registry.supports("TIME_SLOT")).isFalse();
        assertThat(registry.models()).containsExactly("CAPACITY", "TIME_RANGE");
    }

    @Test
    void newModelsPlugInWithoutCoreChanges() {
        InventoryStrategy custom = strategy("WAITLIST");
        InventoryStrategyRegistry registry = new InventoryStrategyRegistry(List.of(custom));
        assertThat(registry.get("WAITLIST")).isSameAs(custom);
    }

    @Test
    void rejectsDuplicatesAndUnknownModels() {
        assertThatThrownBy(() -> new InventoryStrategyRegistry(List.of(strategy("CAPACITY"), strategy("CAPACITY"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        InventoryStrategyRegistry registry = new InventoryStrategyRegistry(List.of(strategy("CAPACITY")));
        assertThatThrownBy(() -> registry.get("NOPE")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> registry.get(null)).isInstanceOf(IllegalStateException.class);
    }
}
