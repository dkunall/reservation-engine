package com.reservation.engine.waitlist;

import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.application.HoldExecutor;
import com.reservation.engine.config.WaitlistProperties;
import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.exception.AccessForbiddenException;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.observability.ReservationMetrics;
import com.reservation.engine.outbox.OutboxWriter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Join / inspect / leave the waitlist. Promotion itself is done asynchronously by {@link WaitlistPromoter}. */
@Service
public class WaitlistService {

    private final WaitlistRepository entries;
    private final WaitlistSignals signals;
    private final HoldExecutor holds;
    private final OutboxWriter outbox;
    private final ReservationMetrics metrics;
    private final WaitlistProperties props;
    private final Clock clock;

    public WaitlistService(WaitlistRepository entries, WaitlistSignals signals, HoldExecutor holds,
                           OutboxWriter outbox, ReservationMetrics metrics, WaitlistProperties props, Clock clock) {
        this.entries = entries;
        this.signals = signals;
        this.holds = holds;
        this.outbox = outbox;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Validates the request exactly like a hold would (same resource type + inventory strategy rules), stores a
     * WAITING entry and writes a JOINED signal so the promoter checks immediately (inventory may already be free).
     *
     * @param waitFor requested lifetime, capped at the configured max and at the requested start time
     */
    @Transactional
    public WaitlistEntryView join(CurrentUser user, UUID resourceId, AllocationRequest request, Duration waitFor) {
        holds.plan(resourceId, request); // throws NotFound / InvalidRequest
        Instant now = clock.instant();
        Duration ttl = waitFor == null ? props.defaultTtl() : waitFor;
        if (ttl.compareTo(props.maxTtl()) > 0) {
            ttl = props.maxTtl();
        }
        Instant expiresAt = now.plus(ttl);
        if (request.startsAt() != null && request.startsAt().isBefore(expiresAt)) {
            expiresAt = request.startsAt();
        }
        if (!expiresAt.isAfter(now)) {
            throw new InvalidRequestException("Cannot wait for a booking that starts in the past");
        }
        WaitlistEntry entry = entries.saveAndFlush(WaitlistEntry.join(user.userId(), resourceId, request, expiresAt, now));
        signals.signal(resourceId, "JOINED", now);
        event(entry, "WaitlistJoined");
        metrics.waitlist("joined");
        return WaitlistEntryView.from(entry);
    }

    @Transactional(readOnly = true)
    public WaitlistEntryView get(CurrentUser user, UUID id) {
        WaitlistEntry e = entries.findById(id).orElseThrow(() -> new NotFoundException("Waitlist entry " + id + " not found"));
        assertCanAccess(user, e);
        return WaitlistEntryView.from(e);
    }

    @Transactional(readOnly = true)
    public Page<WaitlistEntryView> listOwn(CurrentUser user, WaitlistStatus status, Pageable pageable) {
        Page<WaitlistEntry> page = status == null ? entries.findByUserId(user.userId(), pageable)
                : entries.findByUserIdAndStatus(user.userId(), status, pageable);
        return page.map(WaitlistEntryView::from);
    }

    /** Leaves the queue. Row lock serialises against a concurrent promotion: exactly one of them wins. */
    @Transactional
    public WaitlistEntryView cancel(CurrentUser user, UUID id) {
        WaitlistEntry e = entries.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Waitlist entry " + id + " not found"));
        assertCanAccess(user, e);
        e.cancel(clock.instant());
        event(e, "WaitlistCancelled");
        metrics.waitlist("cancelled");
        return WaitlistEntryView.from(e);
    }

    private static void assertCanAccess(CurrentUser user, WaitlistEntry e) {
        if (!user.admin() && !e.isOwnedBy(user.userId())) {
            throw new AccessForbiddenException("Waitlist entry " + e.getId() + " belongs to another user");
        }
    }

    void event(WaitlistEntry e, String type) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("waitlistEntryId", e.getId().toString());
        payload.put("userId", e.getUserId());
        payload.put("resourceId", e.getResourceId().toString());
        payload.put("status", e.getStatus().name());
        if (e.getPromotedReservationId() != null) {
            payload.put("reservationId", e.getPromotedReservationId().toString());
        }
        outbox.append("WaitlistEntry", e.getId(), type, payload);
    }
}
