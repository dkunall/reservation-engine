package com.reservation.engine.waitlist;

import com.reservation.engine.application.HoldExecutor;
import com.reservation.engine.config.WaitlistProperties;
import com.reservation.engine.domain.Reservation;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.observability.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Turns released inventory into HELD reservations for waiting users.
 *
 * <p>Per signal (claimed with FOR UPDATE SKIP LOCKED, so any number of promoter instances can run):
 * walk WAITING entries of that resource FIFO ({@code requested_at, id}); for each, in its OWN short transaction:
 * lock the entry ({@code FOR UPDATE SKIP LOCKED}, still WAITING), place a hold through {@link HoldExecutor} as the
 * entry's user (normal TTL), and mark the entry PROMOTED with the reservation id - hold and mark commit together,
 * so an entry can never be promoted twice and a promotion can never be lost. The inventory strategy (conditional
 * update / constraints) decides how many promotions the released inventory can satisfy; no extra bookkeeping.
 * Entries whose request cannot be satisfied right now stay WAITING ("next eligible", not strict head-of-line).
 */
@Component
public class WaitlistPromoter {
    private static final Logger log = LoggerFactory.getLogger(WaitlistPromoter.class);

    public record Report(int signals, int promoted, int unavailable, int invalidated) {
        Report plus(Report o) {
            return new Report(signals + o.signals, promoted + o.promoted, unavailable + o.unavailable,
                    invalidated + o.invalidated);
        }
    }

    private enum Attempt { PROMOTED, UNAVAILABLE, INVALID, SKIPPED }

    private final WaitlistRepository entries;
    private final WaitlistSignals signals;
    private final WaitlistService service;
    private final HoldExecutor holds;
    private final ReservationMetrics metrics;
    private final WaitlistProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate requiresNew;

    public WaitlistPromoter(WaitlistRepository entries, WaitlistSignals signals, WaitlistService service,
                            HoldExecutor holds, ReservationMetrics metrics, WaitlistProperties props, Clock clock,
                            PlatformTransactionManager txManager) {
        this.entries = entries;
        this.signals = signals;
        this.service = service;
        this.holds = holds;
        this.metrics = metrics;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${reservation.waitlist.promote-interval:PT1S}")
    public void scheduledPromote() {
        Report r = runOnce();
        if (r.promoted() + r.invalidated() > 0) {
            log.info("waitlist promotion {}", r);
        }
    }

    /** Safety net: re-signal every resource that still has waiters (covers a release racing with a join). */
    @Scheduled(fixedDelayString = "${reservation.waitlist.rescan-interval:PT1M}")
    public void scheduledRescan() {
        rescan();
    }

    @Scheduled(fixedDelayString = "${reservation.waitlist.expiry-interval:PT30S}")
    public void scheduledExpiry() {
        int n = expireEntries();
        if (n > 0) {
            log.info("waitlist entries expired={}", n);
        }
        signals.purgeProcessed(clock.instant().minus(Duration.ofHours(1)));
    }

    /** Processes up to {@code signalsPerRun} signals. Safe to call concurrently from many threads / nodes. */
    public Report runOnce() {
        Report total = new Report(0, 0, 0, 0);
        for (int i = 0; i < props.signalsPerRun(); i++) {
            Report r = tx.execute(s -> signals.claimNext(clock.instant())
                    .map(claimed -> promoteFor(claimed.resourceId()))
                    .orElse(null));
            if (r == null) {
                break;
            }
            total = total.plus(r);
        }
        return total;
    }

    public int rescan() {
        Integer n = tx.execute(s -> {
            List<UUID> ids = entries.findResourcesWithWaitingEntries();
            Instant now = clock.instant();
            ids.forEach(id -> signals.signal(id, "RESCAN", now));
            return ids.size();
        });
        return n == null ? 0 : n;
    }

    private Report promoteFor(UUID resourceId) {
        int promoted = 0;
        int unavailable = 0;
        int invalid = 0;
        List<UUID> candidates = entries.findWaitingFifo(resourceId, clock.instant(), props.candidatesPerSignal());
        for (UUID entryId : candidates) {
            Attempt a = attempt(entryId);
            switch (a) {
                case PROMOTED -> promoted++;
                case UNAVAILABLE -> unavailable++;
                case INVALID -> invalid++;
                default -> {
                }
            }
        }
        return new Report(1, promoted, unavailable, invalid);
    }

    private Attempt attempt(UUID entryId) {
        String[] invalidReason = new String[1];
        Attempt result;
        try {
            result = requiresNew.execute(status -> {
                Optional<WaitlistEntry> locked = entries.lockIfWaiting(entryId);
                if (locked.isEmpty()) {
                    return Attempt.SKIPPED; // promoted/cancelled already, or another promoter holds it right now
                }
                WaitlistEntry entry = locked.get();
                Instant now = clock.instant();
                if (entry.isExpired(now)) {
                    return Attempt.SKIPPED; // the expiry job owns it
                }
                try {
                    HoldExecutor.HoldPlan plan = holds.plan(entry.getResourceId(), entry.toAllocationRequest());
                    Reservation r = holds.execute(entry.getUserId(), plan, null, null);
                    entry.promote(r.getId(), now);
                    service.event(entry, "WaitlistPromoted");
                    return Attempt.PROMOTED;
                } catch (InventoryUnavailableException e) {
                    status.setRollbackOnly();
                    return Attempt.UNAVAILABLE;
                } catch (InvalidRequestException | NotFoundException e) {
                    status.setRollbackOnly();
                    invalidReason[0] = e.getMessage();
                    return Attempt.INVALID;
                }
            });
        } catch (PessimisticLockingFailureException e) {
            log.info("waitlist promotion lock conflict entry={}; will retry on next signal", entryId);
            return Attempt.UNAVAILABLE;
        }
        if (result == Attempt.PROMOTED) {
            metrics.waitlist("promoted");
        } else if (result == Attempt.INVALID) {
            invalidate(entryId, invalidReason[0]);
        }
        return result == null ? Attempt.SKIPPED : result;
    }

    /** The request can never be satisfied any more (e.g. resource deactivated, start time passed). */
    private void invalidate(UUID entryId, String reason) {
        requiresNew.executeWithoutResult(s -> entries.lockIfWaiting(entryId).ifPresent(e -> {
            e.expire("no longer satisfiable: " + reason, clock.instant());
            service.event(e, "WaitlistExpired");
            metrics.waitlist("invalidated");
        }));
    }

    public int expireEntries() {
        int total = 0;
        while (true) {
            Integer n = tx.execute(s -> {
                Instant now = clock.instant();
                List<WaitlistEntry> batch = entries.lockExpired(now, 200);
                batch.forEach(e -> {
                    e.expire("waitlist entry expired", now);
                    service.event(e, "WaitlistExpired");
                });
                return batch.size();
            });
            if (n == null || n == 0) {
                return total;
            }
            total += n;
            metrics.waitlist("expired");
        }
    }
}
