package com.reservation.engine.api.dto;

import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Resource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ResourceResponse(UUID id, String type, String inventoryModel, String name, String description,
                               Map<String, Object> attributes, Money price, boolean active, Instant createdAt) {
    public static ResourceResponse from(Resource r) {
        return new ResourceResponse(r.getId(), r.getTypeCode(), r.getInventoryModel(), r.getName(), r.getDescription(),
                r.getAttributes(), r.getPrice(), r.isActive(), r.getCreatedAt());
    }
}
