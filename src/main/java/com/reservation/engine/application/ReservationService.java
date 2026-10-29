package com.reservation.engine.application;

import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.exception.CancellationNotAllowedException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.policy.CancellationDecision;
import com.reservation.engine.domain.policy.HoldTtlPolicy;
import com.reservation.engine.inventory.spi.InventoryStrategyRegistry;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ReservationRepository;
import com.reservation.engine.persistence.ResourceRepository;
import com.reservation.engine.resourcetype.ResourceTypeRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Core workflow, identical for every resource type. Type/model specifics are delegated to the
 * {@code ResourceTypeDefinition} and the {@code InventoryStrategy} resolved from registries.
 */
@Service
public class ReservationService {

    public record HoldCommand(UUID resourceId, AllocationRequest request) {
    }

    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final PolicyResolver policies;
    private final InventoryAllocator inventory;
    private final ReservationEventRecorder recorder;
    private final ReservationMetrics metrics;
    private final Clock clock;
    private final HoldExecutor holds;

    /** Convenience constructor (unit tests): builds the stateless {@link HoldExecutor} from the same collaborators. */
    public ReservationService(ResourceRepository resources, ReservationRepository reservations,
                              PaymentTransactionRepository payments, ResourceTypeRegistry types,
                              InventoryStrategyRegistry strategies, HoldTtlPolicy holdTtlPolicy, PolicyResolver policies,
                              InventoryAllocator inventory, ReservationEventRecorder recorder,
                              ReservationMetrics metrics, Clock clock) {
        this(reservations, payments, policies, inventory, recorder, metrics, clock,
                new HoldExecutor(resources, reservations, types, strategies, holdTtlPolicy, recorder, metrics, clock));
    }

    @Autowired
    public ReservationService(ReservationRepository reservations, PaymentTransactionRepository payments,
                              PolicyResolver policies, InventoryAllocator inventory, ReservationEventRecorder recorder,
                              ReservationMetrics metrics, Clock clock, HoldExecutor holds) {
        this.reservations = reservations;
        this.payments = payments;
        this.policies = policies;
        this.inventory = inventory;
        this.recorder = recorder;
        this.metrics = metrics;
        this.clock = clock;
        this.holds = holds;
    }

    /** Allocate inventory + persist the HELD reservation (+ audit + outbox) in ONE short transaction. */
    @Transactional
    public ReservationView hold(CurrentUser user, HoldCommand cmd) {
        return metrics.time("reservation.hold.duration", () -> doHold(user, cmd));
    }

    private ReservationView doHold(CurrentUser user, HoldCommand cmd) {
        HoldExecutor.HoldPlan plan = holds.plan(cmd.resourceId(), cmd.request());
        return ReservationView.from(holds.execute(user.userId(), plan, null, null));
    }

    @Transactional(readOnly = true)
    public ReservationView get(CurrentUser user, UUID id) {
        Reservation r = reservations.findWithResourceById(id)
                .orElseThrow(() -> new NotFoundException("Reservation " + id + " not found"));
        AccessPolicy.assertCanAccess(user, r);
        return ReservationView.from(r);
    }

    @Transactional(readOnly = true)
    public Page<ReservationView> listOwn(CurrentUser user, ReservationStatus status, Pageable pageable) {
        Page<Reservation> page = status == null
                ? reservations.findByUserIdWithResource(user.userId(), pageable)
                : reservations.findByUserIdAndStatusWithResource(user.userId(), status, pageable);
        return page.map(ReservationView::from);
    }

    /** Cancel a HELD or CONFIRMED reservation, release inventory, queue a refund if one is due. */
    @Transactional
    public ReservationView cancel(CurrentUser user, UUID id, String reason) {
        Reservation r = reservations.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Reservation " + id + " not found"));
        AccessPolicy.assertCanAccess(user, r);
        ItineraryGuard.assertStandalone(r);
        Instant now = clock.instant();
        boolean wasConfirmed = r.getStatus() == ReservationStatus.CONFIRMED;
        CancellationDecision decision = policies.cancellationPolicy(r.getResourceType()).evaluate(r, now);
        if (!decision.allowed()) {
            throw new CancellationNotAllowedException(decision.reason());
        }
        r.cancel(user.userId(), reason == null ? "cancelled by user" : reason, now);
        inventory.release(r, now);
        if (wasConfirmed && decision.refund() != null && !decision.refund().isZero()) {
            payments.save(new PaymentTransaction(r.getId(), PaymentTransaction.Type.REFUND, decision.refund(), now));
        }
        recorder.record(r);
        metrics.cancelled(r.getResourceType());
        return ReservationView.from(r);
    }
}
