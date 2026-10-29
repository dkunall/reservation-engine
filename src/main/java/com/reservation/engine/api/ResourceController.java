package com.reservation.engine.api;

import com.reservation.engine.api.dto.CreateResourceRequest;
import com.reservation.engine.api.dto.InventoryRequest;
import com.reservation.engine.api.dto.PageResponse;
import com.reservation.engine.api.dto.ResourceResponse;
import com.reservation.engine.api.dto.ResourceTypeResponse;
import com.reservation.engine.application.ResourceService;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.inventory.spi.AvailabilityQuery;
import com.reservation.engine.inventory.spi.AvailabilityView;
import com.reservation.engine.inventory.spi.InventorySetup;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Validated
public class ResourceController {

    private final ResourceService resources;
    private final ResourceTypeRegistry types;

    public ResourceController(ResourceService resources, ResourceTypeRegistry types) {
        this.resources = resources;
        this.types = types;
    }

    @PostMapping("/resources")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResourceResponse> create(@Valid @RequestBody CreateResourceRequest req) {
        Money price;
        try {
            price = new Money(req.price().amount(), req.price().currency());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(e.getMessage());
        }
        var created = resources.create(new ResourceService.CreateResourceCommand(req.type(), req.name(),
                req.description(), req.attributes(), price, toSetup(req.inventory())));
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(ResourceResponse.from(created));
    }

    @GetMapping("/resources")
    public PageResponse<ResourceResponse> list(@RequestParam(required = false) String type,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(resources.list(type, pageable).map(ResourceResponse::from));
    }

    @GetMapping("/resources/{id}")
    public ResourceResponse get(@PathVariable UUID id) {
        return ResourceResponse.from(resources.get(id));
    }

    @PostMapping("/resources/{id}/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ResourceResponse addInventory(@PathVariable UUID id, @Valid @RequestBody InventoryRequest req) {
        return ResourceResponse.from(resources.addInventory(id, toSetup(req)));
    }

    @GetMapping("/resources/{id}/availability")
    public AvailabilityView availability(@PathVariable UUID id,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                         @RequestParam(defaultValue = "1") @Min(1) @Max(100) int quantity) {
        return resources.availability(id, new AvailabilityQuery(from, to, quantity));
    }

    @GetMapping("/resource-types")
    public List<ResourceTypeResponse> resourceTypes() {
        return types.all().stream()
                .map(t -> new ResourceTypeResponse(t.code(), t.inventoryModel(), t.holdTtl().orElse(null), t.billingPeriod()))
                .toList();
    }

    private static InventorySetup toSetup(InventoryRequest req) {
        if (req == null) {
            return null;
        }
        List<InventorySetup.SlotSpec> slots = req.slots() == null ? List.of()
                : req.slots().stream().map(s -> new InventorySetup.SlotSpec(s.startsAt(), s.endsAt(), s.capacity())).toList();
        return new InventorySetup(req.capacity(), req.unitCodes(), slots, req.buckets());
    }
}
