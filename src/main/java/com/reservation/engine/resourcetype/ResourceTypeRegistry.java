package com.reservation.engine.resourcetype;

import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Registry of all resource type definitions; validated at startup against available inventory strategies. */
public class ResourceTypeRegistry {
    private final Map<String, ResourceTypeDefinition> byCode;

    public ResourceTypeRegistry(List<ResourceTypeDefinition> definitions, InventoryStrategyRegistry strategies) {
        Map<String, ResourceTypeDefinition> m = new TreeMap<>();
        for (ResourceTypeDefinition d : definitions) {
            if (d.code() == null || !d.code().matches("^[A-Z][A-Z0-9_]*$")) {
                throw new IllegalStateException("Invalid resource type code: " + d.code());
            }
            if (!strategies.supports(d.inventoryModel())) {
                throw new IllegalStateException("Resource type " + d.code() + " uses unknown inventory model " + d.inventoryModel());
            }
            ResourceTypeDefinition prev = m.putIfAbsent(d.code(), d);
            if (prev != null) {
                throw new IllegalStateException("Duplicate resource type definition: " + d.code());
            }
        }
        this.byCode = Collections.unmodifiableMap(m);
    }

    public ResourceTypeDefinition get(String code) {
        ResourceTypeDefinition d = code == null ? null : byCode.get(code);
        if (d == null) {
            throw new InvalidRequestException("Unsupported resource type: " + code);
        }
        return d;
    }

    public java.util.Optional<ResourceTypeDefinition> find(String code) {
        return java.util.Optional.ofNullable(code == null ? null : byCode.get(code));
    }

    public Collection<ResourceTypeDefinition> all() {
        return byCode.values();
    }
}
