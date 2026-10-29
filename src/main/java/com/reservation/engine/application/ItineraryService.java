package com.reservation.engine.application;

import com.reservation.engine.config.ItineraryProperties;
import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.Itinerary;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.domain.exception.CancellationNotAllowedException;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.policy.CancellationDecision;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.payment.PaymentTransaction;
import com.reservation.engine.payment.PaymentTransactionRepository;
import com.reservation.engine.persistence.ItineraryRepository;
import com.reservation.engine.persistence.ReservationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Atomic multi-resource booking. {@link #hold} places ALL item holds in ONE short transaction or none:
 * any {@link InventoryUnavailableException} rolls back every allocation already made.
 *
 * <p>Deadlock avoidance: items are allocated in a deterministic global order ({@link #LOCK_ORDER}: strategy lock
 * rank, resource id, then start time / slot / unit codes), so two itineraries touching the same resources acquire the hot rows in the
 * same order. Residual deadlocks (e.g. unit rows inside one resource) are resolved by the database and retried as a
 * whole transaction by the caller ({@link LockConflictRetry}).
 */
@Service
public class ItineraryService {

    public record ItemCommand(UUID resourceId, AllocationRequest request) {
    }

    /**
     * Global allocation order across all itineraries (and therefore lock acquisition order): strategy lock rank
     * first (hot shared counters last, so they are held for the shortest time), then resource id, then start time /
     * slot / unit codes.
     */
    static final Comparator<HoldExecutor.HoldPlan> LOCK_ORDER = Comparator
            .comparingInt((HoldExecutor.HoldPlan p) -> p.strategy() == null ? 0 : p.strategy().lockOrderRank())
            .thenComparing((HoldExecutor.HoldPlan p) -> p.resource().getId())
            .thenComparing(p -> p.request().startsAt(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(p -> p.request().slotId(), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(p -> String.join(",", p.request().unitCodes().stream().sorted().toList()));

    private final ItineraryRepository itineraries;
    private final ReservationRepository reservations;
    private final PaymentTransactionRepository payments;
    private final HoldExecutor holds;
    private final ItineraryLifecycle lifecycle;
    private final ItineraryEventRecorder recorder;
    private final PolicyResolver policies;
    private final ReservationMetrics metrics;
    private final Clock clock;
    private final ItineraryProperties props;

    public ItineraryService(ItineraryRepository itineraries, ReservationRepository reservations,
                            PaymentTransactionRepository payments, HoldExecutor holds, ItineraryLifecycle lifecycle,
                            ItineraryEventRecorder recorder, PolicyResolver policies, ReservationMetrics metrics,
                            Clock clock, ItineraryProperties props) {
        this.itineraries = itineraries;
        this.reservations = reservations;
        this.payments = payments;
        this.holds = holds;
        this.lifecycle = lifecycle;
        this.recorder = recorder;
        this.policies = policies;
        this.metrics = metrics;
        this.clock = clock;
        this.props = props;
    }

    @Transactional
    public ItineraryView hold(CurrentUser user, List<ItemCommand> items) {
        return metrics.time("itinerary.hold.duration", () -> doHold(user, items));
    }

    private ItineraryView doHold(CurrentUser user, List<ItemCommand> items) {
        if (items == null || items.isEmpty() || items.size() > props.maxItems()) {
            throw new InvalidRequestException("An itinerary needs between 1 and " + props.maxItems() + " items");
        }
        // phase 1: validate + price every item before touching inventory
        List<HoldExecutor.HoldPlan> plans = new ArrayList<>(items.size());
        for (ItemCommand item : items) {
            plans.add(holds.plan(item.resourceId(), item.request()));
        }
        String currency = plans.get(0).total().currency();
        BigDecimal sum = BigDecimal.ZERO;
        Duration ttl = plans.get(0).ttl();
        for (HoldExecutor.HoldPlan p : plans) {
            if (!p.total().currency().equals(currency)) {
                throw new InvalidRequestException("All itinerary items must be priced in the same currency");
            }
            sum = sum.add(p.total().amount());
            ttl = p.ttl().compareTo(ttl) < 0 ? p.ttl() : ttl; // the whole itinerary expires with its shortest hold
        }
        Instant now = clock.instant();
        Itinerary itinerary = Itinerary.hold(user.userId(), plans.size(), new Money(sum, currency), now.plus(ttl), now);
        itineraries.saveAndFlush(itinerary);

        // phase 2: allocate in global lock order; first failure aborts (and rolls back) everything
        List<HoldExecutor.HoldPlan> ordered = plans.stream().sorted(LOCK_ORDER).toList();
        List<Reservation> held = new ArrayList<>(ordered.size());
        try {
            for (HoldExecutor.HoldPlan plan : ordered) {
                held.add(holds.execute(user.userId(), plan, itinerary.getHoldExpiresAt(), itinerary.getId()));
            }
        } catch (InventoryUnavailableException e) {
            metrics.itineraryHold("rejected");
            throw new InventoryUnavailableException("Itinerary rejected, nothing was held: " + e.getMessage());
        }
        recorder.record(itinerary, held);
        metrics.itineraryHold("created");
        return ItineraryView.from(itinerary, held);
    }

    @Transactional(readOnly = true)
    public ItineraryView get(CurrentUser user, UUID id) {
        Itinerary it = itineraries.findById(id)
                .orElseThrow(() -> new NotFoundException("Itinerary " + id + " not found"));
        AccessPolicy.assertCanAccess(user, it);
        return ItineraryView.from(it, reservations.findByItineraryIdWithResource(id));
    }

    /** Listing omits items (one query per page, no N+1); fetch a single itinerary for its items. */
    @Transactional(readOnly = true)
    public Page<ItineraryView> listOwn(CurrentUser user, ReservationStatus status, Pageable pageable) {
        Page<Itinerary> page = status == null
                ? itineraries.findByUserId(user.userId(), pageable)
                : itineraries.findByUserIdAndStatus(user.userId(), status, pageable);
        return page.map(it -> ItineraryView.from(it, null));
    }

    /**
     * Cancels the itinerary and every item as a unit. Each item's cancellation policy is evaluated; if any item
     * may not be cancelled, nothing is. Refunds of confirmed items are summed into ONE itinerary refund.
     */
    @Transactional
    public ItineraryView cancel(CurrentUser user, UUID id, String reason) {
        Itinerary it = itineraries.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Itinerary " + id + " not found"));
        AccessPolicy.assertCanAccess(user, it);
        List<Reservation> items = reservations.findByItineraryIdForUpdate(id);
        Instant now = clock.instant();
        boolean wasConfirmed = it.getStatus() == ReservationStatus.CONFIRMED;
        BigDecimal refund = BigDecimal.ZERO;
        for (Reservation r : items) {
            CancellationDecision decision = policies.cancellationPolicy(r.getResourceType()).evaluate(r, now);
            if (!decision.allowed()) {
                throw new CancellationNotAllowedException("Item " + r.getId() + ": " + decision.reason());
            }
            if (decision.refund() != null) {
                refund = refund.add(decision.refund().amount());
            }
        }
        lifecycle.cancel(it, items, user.userId(), reason == null ? "cancelled by user" : reason, now);
        if (wasConfirmed && refund.signum() > 0) {
            payments.save(PaymentTransaction.forItinerary(it.getId(), PaymentTransaction.Type.REFUND,
                    new Money(refund, it.getTotal().currency()), now));
        }
        recorder.record(it, items);
        items.forEach(r -> metrics.cancelled(r.getResourceType()));
        return ItineraryView.from(it, items);
    }
}
