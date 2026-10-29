package com.reservation.engine.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record CreateResourceRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,49}$") String type,
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @Size(max = 50) Map<String, Object> attributes,
        @NotNull @Valid MoneyDto price,
        @Valid InventoryRequest inventory) {
}
