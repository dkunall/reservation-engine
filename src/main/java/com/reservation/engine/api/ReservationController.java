package com.reservation.engine.api;

import com.reservation.engine.api.dto.CancelReservationRequest;
import com.reservation.engine.api.dto.ConfirmReservationRequest;
import com.reservation.engine.api.dto.CreateReservationRequest;
import com.reservation.engine.api.dto.PageResponse;
import com.reservation.engine.application.ConfirmResult;
import com.reservation.engine.application.ConfirmationService;
import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.application.ReservationService;
import com.reservation.engine.application.ReservationView;
import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.ReservationStatus;
import com.reservation.engine.idempotency.IdempotencyService;
import com.reservation.engine.idempotency.RequestHasher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reservations")
@Validated
public class ReservationController {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ReservationService reservations;
    private final ConfirmationService confirmations;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;

    public ReservationController(ReservationService reservations, ConfirmationService confirmations,
                                 IdempotencyService idempotency, RequestHasher hasher) {
        this.reservations = reservations;
        this.confirmations = confirmations;
        this.idempotency = idempotency;
        this.hasher = hasher;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(IDEMPOTENCY_HEADER) String key,
                                    @Valid @RequestBody CreateReservationRequest req,
                                    Authentication auth) {
        CurrentUser user = Principals.of(auth);
        AllocationRequest allocation = toAllocation(req);
        var idem = new IdempotencyService.Request(user.userId(), key, "CREATE_RESERVATION",
                hasher.hash("CREATE_RESERVATION", "", req));
        return idempotency.execute(idem, IdempotencyService.Mode.ATOMIC, () -> {
            ReservationView view = reservations.hold(user, new ReservationService.HoldCommand(req.resourceId(), allocation));
            return ResponseEntity.created(URI.create("/api/v1/reservations/" + view.id())).body(view);
        });
    }

    /** Same defaulting rules wherever the generic request shape is accepted (reservations, itineraries). */
    static AllocationRequest toAllocation(CreateReservationRequest req) {
        return toAllocation(req.quantity(), req.unitCodes(), req.slotId(), req.startsAt(), req.endsAt());
    }

    static AllocationRequest toAllocation(Integer requestedQuantity, List<String> unitCodes, UUID slotId,
                                          java.time.Instant startsAt, java.time.Instant endsAt) {
        List<String> units = unitCodes == null ? List.of() : unitCodes;
        int quantity = requestedQuantity != null ? requestedQuantity : (units.isEmpty() ? 1 : units.size());
        return new AllocationRequest(quantity, units, slotId, startsAt, endsAt);
    }

    @GetMapping("/{id}")
    public ReservationView get(@PathVariable UUID id, Authentication auth) {
        return reservations.get(Principals.of(auth), id);
    }

    @GetMapping
    public PageResponse<ReservationView> listOwn(@RequestParam(required = false) ReservationStatus status,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                                 Authentication auth) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(reservations.listOwn(Principals.of(auth), status, pageable));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable UUID id,
                                     @RequestHeader(IDEMPOTENCY_HEADER) String key,
                                     @Valid @RequestBody(required = false) ConfirmReservationRequest req,
                                     Authentication auth) {
        CurrentUser user = Principals.of(auth);
        var idem = new IdempotencyService.Request(user.userId(), key, "CONFIRM_RESERVATION",
                hasher.hash("CONFIRM_RESERVATION", id.toString(), req));
        String token = req == null ? null : req.paymentMethodToken();
        return idempotency.execute(idem, IdempotencyService.Mode.SEPARATE, () -> {
            ConfirmResult result = confirmations.confirm(user, id, token);
            HttpStatus status = result.outcome() == ConfirmResult.Outcome.PENDING ? HttpStatus.ACCEPTED : HttpStatus.OK;
            return ResponseEntity.status(status).body(result.reservation());
        });
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id,
                                    @RequestHeader(IDEMPOTENCY_HEADER) String key,
                                    @Valid @RequestBody(required = false) CancelReservationRequest req,
                                    Authentication auth) {
        CurrentUser user = Principals.of(auth);
        var idem = new IdempotencyService.Request(user.userId(), key, "CANCEL_RESERVATION",
                hasher.hash("CANCEL_RESERVATION", id.toString(), req));
        String reason = req == null ? null : req.reason();
        return idempotency.execute(idem, IdempotencyService.Mode.ATOMIC,
                () -> ResponseEntity.ok(reservations.cancel(user, id, reason)));
    }
}
