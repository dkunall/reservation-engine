package com.reservation.engine.application;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.AllocationResult;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.policy.HoldTtlPolicy;
import com.reservation.engine.inventory.spi.InventoryStrategy;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.persistence.ReservationRepository;
import com.reservation.engine.persistence.ResourceRepository;
import com.reservation.engine.resourcetype.ResourceTypeDefinition;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The single place where a HELD reservation is created. Two phases so composite use cases can make decisions
 * across several holds before touching inventory:
 * <ol>
 *   <li>{@link #plan}: load + validate resource and request, price it, compute its hold TTL (no writes).</li>
 *   <li>{@link #execute}: persist the reservation, allocate via the inventory strategy, record audit/outbox.
 *       Must run inside the caller's transaction.</li>
 * </ol>
 * Used by single reservations, itineraries (one call per item, same transaction) and waitlist promotion.
 * Stateless.
 */
@Component
public class HoldExecutor {

    /** Validated, priced intent to hold one resource. */
    public record HoldPlan(Resource resource, ResourceTypeDefinition type, InventoryStrategy strategy,
                           AllocationRequest request, Money total, Duration ttl) {
    }

    private final ResourceRepository resources;
    private final ReservationRepository reservations;
    private final ResourceTypeRegistry types;
    private final InventoryStrategyRegistry strategies;
    private final HoldTtlPolicy holdTtlPolicy;
    private final ReservationEventRecorder recorder;
    private final ReservationMetrics metrics;
    private final Clock clock;

    public HoldExecutor(ResourceRepository resources, ReservationRepository reservations, ResourceTypeRegistry types,
                        InventoryStrategyRegistry strategies, HoldTtlPolicy holdTtlPolicy,
                        ReservationEventRecorder recorder, ReservationMetrics metrics, Clock clock) {
        this.resources = resources;
        this.reservations = reservations;
        this.types = types;
        this.strategies = strategies;
        this.holdTtlPolicy = holdTtlPolicy;
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
    }

    public HoldPlan plan(UUID resourceId, AllocationRequest request) {
        Resource resource = resources.findById(resourceId)
                .orElseThrow(() -> new NotFoundException("Resource " + resourceId + " not found"));
        if (!resource.isActive()) {
            throw new InvalidRequestException("Resource " + resource.getId() + " is not bookable");
        }
        ResourceTypeDefinition type = types.get(resource.getTypeCode());
        InventoryStrategy strategy = strategies.get(resource.getInventoryModel());
        strategy.validateRequest(resource, request);
        type.validateRequest(request);
        Money total = resource.getPrice().times(strategy.billableUnits(request, type));
        return new HoldPlan(resource, type, strategy, request, total, holdTtlPolicy.holdTtl(type));
    }

    /**
     * Persists and allocates. Throws {@link InventoryUnavailableException} (caller's transaction must roll back).
     *
     * @param holdExpiresAt null = now + plan TTL
     * @param itineraryId   null for standalone reservations
     */
    public Reservation execute(String userId, HoldPlan plan, Instant holdExpiresAt, UUID itineraryId) {
        Instant now = clock.instant();
        AllocationRequest request = plan.request();
        Instant expiresAt = holdExpiresAt != null ? holdExpiresAt : now.plus(plan.ttl());
        Reservation reservation = Reservation.hold(userId, plan.resource(), request.quantity(), request.startsAt(),
                request.endsAt(), request.slotId(), plan.total(), expiresAt, now);
        if (itineraryId != null) {
            reservation.attachToItinerary(itineraryId);
        }
        reservations.saveAndFlush(reservation); // allocation rows reference it (FK)
        try {
            AllocationResult allocation = plan.strategy().allocate(plan.resource(), reservation.getId(), request);
            reservation.applyAllocation(allocation);
        } catch (InventoryUnavailableException e) {
            metrics.conflict(plan.type().code(), plan.strategy().model(), "hold");
            throw e;
        }
        recorder.record(reservation);
        metrics.holdCreated(plan.type().code(), plan.strategy().model());
        return reservation;
    }
}
