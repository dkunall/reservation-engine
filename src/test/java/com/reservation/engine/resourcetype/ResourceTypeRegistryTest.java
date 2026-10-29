package com.reservation.engine.resourcetype;

import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.inventory.spi.InventoryStrategy;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceTypeRegistryTest {

    private final InventoryStrategyRegistry strategies;

    ResourceTypeRegistryTest() {
        InventoryStrategy s = mock(InventoryStrategy.class);
        when(s.model()).thenReturn("TIME_RANGE");
        strategies = new InventoryStrategyRegistry(List.of(s));
    }

    @Test
    void registersAndResolvesTypes() {
        var reg = new ResourceTypeRegistry(List.of(new SimpleResourceTypeDefinition("HOTEL_ROOM", "TIME_RANGE")), strategies);
        assertThat(reg.get("HOTEL_ROOM").inventoryModel()).isEqualTo("TIME_RANGE");
        assertThat(reg.find("NOPE")).isEmpty();
        assertThatThrownBy(() -> reg.get("NOPE")).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void failsFastOnMisconfiguration() {
        assertThatThrownBy(() -> new ResourceTypeRegistry(List.of(
                new SimpleResourceTypeDefinition("X", "TIME_RANGE"), new SimpleResourceTypeDefinition("X", "TIME_RANGE")), strategies))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new ResourceTypeRegistry(List.of(new SimpleResourceTypeDefinition("Y", "UNKNOWN")), strategies))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown inventory model");
        assertThatThrownBy(() -> new ResourceTypeRegistry(List.of(new SimpleResourceTypeDefinition("bad-code", "TIME_RANGE")), strategies))
                .isInstanceOf(IllegalStateException.class);
    }
}
