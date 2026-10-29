package com.reservation.engine.api;

import com.reservation.engine.api.dto.JoinWaitlistRequest;
import com.reservation.engine.api.dto.PageResponse;
import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.idempotency.IdempotencyService;
import com.reservation.engine.idempotency.RequestHasher;
import com.reservation.engine.waitlist.WaitlistEntryView;
import com.reservation.engine.waitlist.WaitlistService;
import com.reservation.engine.waitlist.WaitlistStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Waitlist: join when inventory is sold out; when inventory is released the entry is auto-promoted to a HELD
 * reservation ({@code promotedReservationId}) which the user confirms through /api/v1/reservations/{id}/confirm.
 */
@RestController
@RequestMapping("/api/v1/waitlist")
@Validated
public class WaitlistController {

    private final WaitlistService waitlist;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;

    public WaitlistController(WaitlistService waitlist, IdempotencyService idempotency, RequestHasher hasher) {
        this.waitlist = waitlist;
        this.idempotency = idempotency;
        this.hasher = hasher;
    }

    @PostMapping
    public ResponseEntity<?> join(@RequestHeader(ReservationController.IDEMPOTENCY_HEADER) String key,
                                  @Valid @RequestBody JoinWaitlistRequest req, Authentication auth) {
        CurrentUser user = Principals.of(auth);
        var allocation = ReservationController.toAllocation(req.quantity(), req.unitCodes(), req.slotId(),
                req.startsAt(), req.endsAt());
        Duration waitFor = req.maxWaitMinutes() == null ? null : Duration.ofMinutes(req.maxWaitMinutes());
        var idem = new IdempotencyService.Request(user.userId(), key, "JOIN_WAITLIST",
                hasher.hash("JOIN_WAITLIST", "", req));
        return idempotency.execute(idem, IdempotencyService.Mode.ATOMIC, () -> {
            WaitlistEntryView view = waitlist.join(user, req.resourceId(), allocation, waitFor);
            return ResponseEntity.created(URI.create("/api/v1/waitlist/" + view.id())).body(view);
        });
    }

    @GetMapping("/{id}")
    public WaitlistEntryView get(@PathVariable UUID id, Authentication auth) {
        return waitlist.get(Principals.of(auth), id);
    }

    @GetMapping
    public PageResponse<WaitlistEntryView> listOwn(@RequestParam(required = false) WaitlistStatus status,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                   Authentication auth) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(waitlist.listOwn(Principals.of(auth), status, pageable));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id,
                                    @RequestHeader(ReservationController.IDEMPOTENCY_HEADER) String key,
                                    Authentication auth) {
        CurrentUser user = Principals.of(auth);
        var idem = new IdempotencyService.Request(user.userId(), key, "CANCEL_WAITLIST",
                hasher.hash("CANCEL_WAITLIST", id.toString(), null));
        return idempotency.execute(idem, IdempotencyService.Mode.ATOMIC,
                () -> ResponseEntity.ok(waitlist.cancel(user, id)));
    }
}
