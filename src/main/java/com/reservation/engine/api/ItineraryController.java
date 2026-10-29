package com.reservation.engine.api;

import com.reservation.engine.api.dto.CancelReservationRequest;
import com.reservation.engine.api.dto.ConfirmReservationRequest;
import com.reservation.engine.api.dto.CreateItineraryRequest;
import com.reservation.engine.api.dto.PageResponse;
import com.reservation.engine.application.ConfirmResult;
import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.application.ItineraryConfirmResult;
import com.reservation.engine.application.ItineraryConfirmationService;
import com.reservation.engine.application.ItineraryService;
import com.reservation.engine.application.ItineraryView;
import com.reservation.engine.application.LockConflictRetry;
import com.reservation.engine.config.ItineraryProperties;
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

/** Atomic multi-resource bookings: all items held / confirmed / cancelled / expired together. */
@RestController
@RequestMapping("/api/v1/itineraries")
@Validated
public class ItineraryController {

    private final ItineraryService itineraries;
    private final ItineraryConfirmationService confirmations;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final ItineraryProperties props;

    public ItineraryController(ItineraryService itineraries, ItineraryConfirmationService confirmations,
                               IdempotencyService idempotency, RequestHasher hasher, ItineraryProperties props) {
        this.itineraries = itineraries;
        this.confirmations = confirmations;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.props = props;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(ReservationController.IDEMPOTENCY_HEADER) String key,
                                    @Valid @RequestBody CreateItineraryRequest req,
                                    Authentication auth) {
        CurrentUser user = Principals.of(auth);
        List<ItineraryService.ItemCommand> items = req.items().stream()
                .map(i -> new ItineraryService.ItemCommand(i.resourceId(), ReservationController.toAllocation(i)))
                .toList();
        var idem = new IdempotencyService.Request(user.userId(), key, "CREATE_ITINERARY",
                hasher.hash("CREATE_ITINERARY", "", req));
        // each attempt = claim + one transaction (holds + COMPLETED marker); a deadlock-aborted attempt left nothing
        return LockConflictRetry.run(props.lockRetryAttempts(), "CREATE_ITINERARY", () ->
                idempotency.execute(idem, IdempotencyService.Mode.ATOMIC, () -> {
                    ItineraryView view = itineraries.hold(user, items);
                    return ResponseEntity.created(URI.create("/api/v1/itineraries/" + view.id())).body(view);
                }));
    }

    @GetMapping("/{id}")
    public ItineraryView get(@PathVariable UUID id, Authentication auth) {
        return itineraries.get(Principals.of(auth), id);
    }

    @GetMapping
    public PageResponse<ItineraryView> listOwn(@RequestParam(required = false) ReservationStatus status,
                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                               Authentication auth) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        return PageResponse.from(itineraries.listOwn(Principals.of(auth), status, pageable));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable UUID id,
                                     @RequestHeader(ReservationController.IDEMPOTENCY_HEADER) String key,
                                     @Valid @RequestBody(required = false) ConfirmReservationRequest req,
                                     Authentication auth) {
        CurrentUser user = Principals.of(auth);
        var idem = new IdempotencyService.Request(user.userId(), key, "CONFIRM_ITINERARY",
                hasher.hash("CONFIRM_ITINERARY", id.toString(), req));
        String token = req == null ? null : req.paymentMethodToken();
        return idempotency.execute(idem, IdempotencyService.Mode.SEPARATE, () -> {
            ItineraryConfirmResult result = confirmations.confirm(user, id, token);
            HttpStatus status = result.outcome() == ConfirmResult.Outcome.PENDING ? HttpStatus.ACCEPTED : HttpStatus.OK;
            return ResponseEntity.status(status).body(result.itinerary());
        });
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable UUID id,
                                    @RequestHeader(ReservationController.IDEMPOTENCY_HEADER) String key,
                                    @Valid @RequestBody(required = false) CancelReservationRequest req,
                                    Authentication auth) {
        CurrentUser user = Principals.of(auth);
        var idem = new IdempotencyService.Request(user.userId(), key, "CANCEL_ITINERARY",
                hasher.hash("CANCEL_ITINERARY", id.toString(), req));
        String reason = req == null ? null : req.reason();
        return idempotency.execute(idem, IdempotencyService.Mode.ATOMIC,
                () -> ResponseEntity.ok(itineraries.cancel(user, id, reason)));
    }
}
