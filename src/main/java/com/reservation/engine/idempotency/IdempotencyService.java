package com.reservation.engine.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservation.engine.config.ReservationProperties;
import com.reservation.engine.observability.ReservationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Idempotency-Key handling (scope: user + key).
 * <ol>
 *   <li>Claim: INSERT ... ON CONFLICT DO NOTHING in its own committed transaction. Exactly one concurrent
 *       request wins; the others see the existing row.</li>
 *   <li>Existing row: different request hash -> 422; IN_PROGRESS -> 409; COMPLETED -> replay stored response.</li>
 *   <li>Winner executes. In {@link Mode#ATOMIC} the business effect and the COMPLETED marker commit in the SAME
 *       transaction (no window where the effect exists but the key is not completed).
 *       {@link Mode#SEPARATE} is for multi-transaction use cases (confirm) that are idempotent by state anyway.</li>
 *   <li>Failure: the claim is deleted, so a retry can run again (the failed attempt had no effect).</li>
 * </ol>
 */
@Service
public class IdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:\\-]{8,255}$");
    public static final String REPLAY_HEADER = "Idempotent-Replayed";

    public enum Mode { ATOMIC, SEPARATE }

    public record Request(String userId, String key, String operation, String requestHash) {
    }

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate joinOrNew;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final Duration ttl;
    private final Duration inProgressTimeout;
    private final ReservationMetrics metrics;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              PlatformTransactionManager txManager, Clock clock, ReservationProperties props,
                              ReservationMetrics metrics) {
        this.metrics = metrics;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.joinOrNew = new TransactionTemplate(txManager);
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.ttl = props.idempotency().ttl();
        this.inProgressTimeout = props.idempotency().inProgressTimeout();
    }

    public static void validateKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "Idempotency-Key header must be 8-255 characters of [A-Za-z0-9._:-]");
        }
    }

    public ResponseEntity<?> execute(Request req, Mode mode, Supplier<ResponseEntity<?>> action) {
        validateKey(req.key());
        Instant now = clock.instant();
        Optional<Long> claim = requiresNew.execute(s -> {
            repository.deleteExpired(req.userId(), req.key(), now);
            return repository.tryClaim(req.userId(), req.key(), req.operation(), req.requestHash(), now, now.plus(ttl))
                    .or(() -> repository.reclaimStale(req.userId(), req.key(), req.requestHash(),
                            now.minus(inProgressTimeout), now));
        });
        if (claim == null || claim.isEmpty()) {
            return replayOrReject(req);
        }
        long id = claim.get();
        try {
            if (mode == Mode.ATOMIC) {
                return joinOrNew.execute(s -> {
                    ResponseEntity<?> response = action.get();
                    repository.complete(id, response.getStatusCode().value(), serialize(response.getBody()), clock.instant());
                    return response;
                });
            }
            ResponseEntity<?> response = action.get();
            requiresNew.executeWithoutResult(s ->
                    repository.complete(id, response.getStatusCode().value(), serialize(response.getBody()), clock.instant()));
            return response;
        } catch (RuntimeException e) {
            try {
                requiresNew.executeWithoutResult(s -> repository.release(id));
            } catch (RuntimeException releaseError) {
                log.warn("failed to release idempotency claim id={}", id, releaseError);
            }
            throw e;
        }
    }

    private ResponseEntity<?> replayOrReject(Request req) {
        IdempotencyRecord existing = repository.find(req.userId(), req.key())
                .orElseThrow(() -> new IdempotencyInProgressException(
                        "A request with this Idempotency-Key is being processed; retry later"));
        if (!existing.requestHash().equals(req.requestHash()) || !existing.operation().equals(req.operation())) {
            throw new IdempotencyKeyReusedException(
                    "Idempotency-Key was already used with a different request payload");
        }
        if (existing.status() == IdempotencyRecord.Status.IN_PROGRESS) {
            throw new IdempotencyInProgressException(
                    "A request with this Idempotency-Key is being processed; retry later");
        }
        metrics.idempotentReplay(req.operation());
        return ResponseEntity.status(existing.responseStatus())
                .header(REPLAY_HEADER, "true")
                .body(deserialize(existing.responseBody()));
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise response for idempotency store", e);
        }
    }

    private JsonNode deserialize(String body) {
        try {
            return body == null ? null : objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency response", e);
        }
    }
}
