package com.reservation.engine.application;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.AllocationResult;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.Resource;
import com.reservation.engine.domain.exception.AccessForbiddenException;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.policy.DefaultCancellationPolicy;
import com.reservation.engine.domain.policy.DefaultHoldTtlPolicy;
import com.reservation.engine.inventory.spi.InventoryStrategy;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ReservationRepository;
import com.reservation.engine.persistence.ResourceRepository;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import com.reservation.engine.resourcetype.SimpleResourceTypeDefinition;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Core workflow with mocked ports: proves the workflow is model-agnostic (strategy chosen by registry). */
class ReservationServiceTest {

    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private final ResourceRepository resources = mock(ResourceRepository.class);
    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final ReservationEventRecorder recorder = mock(ReservationEventRecorder.class);
    private final InventoryStrategy capacity = mock(InventoryStrategy.class);
    private final InventoryStrategy range = mock(InventoryStrategy.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ReservationService service;
    private Resource gaResource;

    @BeforeEach
    void setUp() {
        when(capacity.model()).thenReturn("CAPACITY");
        when(range.model()).thenReturn("TIME_RANGE");
        InventoryStrategyRegistry strategies = new InventoryStrategyRegistry(List.of(capacity, range));
        ResourceTypeRegistry types = new ResourceTypeRegistry(List.of(
                new SimpleResourceTypeDefinition("EVENT_GA", "CAPACITY", Duration.ofMinutes(5), null),
                new SimpleResourceTypeDefinition("HOTEL_ROOM", "TIME_RANGE")), strategies);
        PolicyResolver policies = new PolicyResolver(types, new DefaultCancellationPolicy(Duration.ofHours(1)));
        service = new ReservationService(resources, reservations, mock(PaymentTransactionRepository.class), types,
                strategies, new DefaultHoldTtlPolicy(Duration.ofMinutes(10), Duration.ofHours(1)), policies,
                new InventoryAllocator(strategies), recorder, new ReservationMetrics(meters), Clock.fixed(now, ZoneOffset.UTC));
        gaResource = new Resource("EVENT_GA", "CAPACITY", "Show", null, Map.of(), Money.of("25.00", "USD"), now);
        when(resources.findById(gaResource.getId())).thenReturn(Optional.of(gaResource));
    }

    @Test
    void holdUsesStrategyOfResourceModelAndPricesViaBillableUnits() {
        AllocationRequest req = new AllocationRequest(3, List.of(), null, null, null);
        when(capacity.billableUnits(eq(req), any())).thenReturn(3L);
        when(capacity.allocate(eq(gaResource), any(), eq(req))).thenReturn(AllocationResult.ofQuantity(3));

        ReservationView view = service.hold(CurrentUser.user("alice"), new ReservationService.HoldCommand(gaResource.getId(), req));

        assertThat(view.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(view.total().amount()).isEqualByComparingTo("75.00");
        assertThat(view.holdExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5))); // type TTL override
        verify(capacity).validateRequest(gaResource, req);
        verify(reservations).saveAndFlush(any(Reservation.class));
        verify(recorder).record(any(Reservation.class));
        verify(range, never()).allocate(any(), any(), any());
        assertThat(meters.counter("reservation.holds", "result", "created", "type", "EVENT_GA", "model", "CAPACITY").count())
                .isEqualTo(1.0);
    }

    @Test
    void conflictIsCountedAndPropagated() {
        AllocationRequest req = new AllocationRequest(1, List.of(), null, null, null);
        when(capacity.billableUnits(any(), any())).thenReturn(1L);
        when(capacity.allocate(any(), any(), any())).thenThrow(new InventoryUnavailableException("sold out"));
        assertThatThrownBy(() -> service.hold(CurrentUser.user("alice"),
                new ReservationService.HoldCommand(gaResource.getId(), req)))
                .isInstanceOf(InventoryUnavailableException.class);
        verify(recorder, never()).record(any());
        assertThat(meters.counter("reservation.conflicts", "type", "EVENT_GA", "model", "CAPACITY", "operation", "hold").count())
                .isEqualTo(1.0);
    }

    @Test
    void invalidRequestNeverTouchesInventoryOrDatabase() {
        AllocationRequest req = new AllocationRequest(1, List.of("X"), null, null, null);
        org.mockito.Mockito.doThrow(new InvalidRequestException("bad")).when(capacity).validateRequest(any(), any());
        assertThatThrownBy(() -> service.hold(CurrentUser.user("alice"),
                new ReservationService.HoldCommand(gaResource.getId(), req)))
                .isInstanceOf(InvalidRequestException.class);
        verify(reservations, never()).saveAndFlush(any());
        verify(capacity, never()).allocate(any(), any(), any());
    }

    @Test
    void unknownResourceIsNotFound() {
        UUID id = UUID.randomUUID();
        when(resources.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.hold(CurrentUser.user("alice"),
                new ReservationService.HoldCommand(id, new AllocationRequest(1, null, null, null, null))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelReleasesThroughSnapshottedModelAndEnforcesOwnership() {
        Reservation r = Reservation.hold("alice", gaResource, 1, null, null, null, Money.of("25", "USD"),
                now.plusSeconds(600), now);
        when(reservations.findByIdForUpdate(r.getId())).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.cancel(CurrentUser.user("mallory"), r.getId(), null))
                .isInstanceOf(AccessForbiddenException.class);

        ReservationView v = service.cancel(CurrentUser.user("alice"), r.getId(), "no longer needed");
        assertThat(v.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(capacity).release(r.getId(), now);
    }
}
