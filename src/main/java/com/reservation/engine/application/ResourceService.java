package com.reservation.engine.application;

import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.inventory.spi.AvailabilityQuery;
import com.reservation.engine.inventory.spi.AvailabilityView;
import com.reservation.engine.inventory.spi.InventorySetup;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import com.reservation.engine.persistence.ResourceRepository;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/** Admin use cases: catalog + inventory provisioning; and the public availability query. */
@Service
public class ResourceService {
    private static final Logger log = LoggerFactory.getLogger(ResourceService.class);

    public record CreateResourceCommand(String type, String name, String description, Map<String, Object> attributes,
                                        Money price, InventorySetup inventory) {
    }

    private final ResourceRepository resources;
    private final ResourceTypeRegistry types;
    private final InventoryStrategyRegistry strategies;
    private final Clock clock;

    public ResourceService(ResourceRepository resources, ResourceTypeRegistry types,
                           InventoryStrategyRegistry strategies, Clock clock) {
        this.resources = resources;
        this.types = types;
        this.strategies = strategies;
        this.clock = clock;
    }

    @Transactional
    public Resource create(CreateResourceCommand cmd) {
        ResourceTypeDefinition type = types.get(cmd.type());
        type.validateAttributes(cmd.attributes() == null ? Map.of() : cmd.attributes());
        Resource resource = new Resource(type.code(), type.inventoryModel(), cmd.name(), cmd.description(),
                cmd.attributes(), cmd.price(), clock.instant());
        resources.saveAndFlush(resource);
        if (cmd.inventory() != null) {
            assertBucketsSupported(resource, cmd.inventory());
            strategies.get(resource.getInventoryModel()).addInventory(resource, cmd.inventory());
        }
        log.info("resource created id={} type={} model={}", resource.getId(), type.code(), type.inventoryModel());
        return resource;
    }

    @Transactional
    public Resource addInventory(UUID resourceId, InventorySetup setup) {
        Resource resource = get(resourceId);
        assertBucketsSupported(resource, setup);
        strategies.get(resource.getInventoryModel()).addInventory(resource, setup);
        return resource;
    }

    private static void assertBucketsSupported(Resource resource, InventorySetup setup) {
        if (setup != null && setup.buckets() != null
                && !com.reservation.engine.inventory.spi.InventoryModels.CAPACITY.equals(resource.getInventoryModel())) {
            throw new com.reservation.engine.domain.exception.InvalidRequestException(
                    "'buckets' is only supported by the CAPACITY inventory model");
        }
    }

    @Transactional(readOnly = true)
    public Resource get(UUID id) {
        return resources.findById(id).orElseThrow(() -> new NotFoundException("Resource " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<Resource> list(String type, Pageable pageable) {
        return type == null ? resources.findAll(pageable) : resources.findByTypeCode(type, pageable);
    }

    @Transactional(readOnly = true)
    public AvailabilityView availability(UUID resourceId, AvailabilityQuery query) {
        Resource resource = get(resourceId);
        return strategies.get(resource.getInventoryModel()).availability(resource, query);
    }
}
