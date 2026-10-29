package com.reservation.engine.api;

import com.reservation.engine.domain.exception.AccessForbiddenException;
import com.reservation.engine.domain.exception.CancellationNotAllowedException;
import com.reservation.engine.domain.exception.ConcurrentModificationException;
import com.reservation.engine.domain.exception.DomainException;
import com.reservation.engine.domain.exception.HoldExpiredException;
import com.reservation.engine.domain.exception.InvalidRequestException;
import com.reservation.engine.domain.exception.InvalidStateTransitionException;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.domain.exception.ItineraryMemberException;
import com.reservation.engine.domain.exception.NotFoundException;
import com.reservation.engine.domain.exception.PaymentDeclinedException;
import com.reservation.engine.idempotency.IdempotencyInProgressException;
import com.reservation.engine.idempotency.IdempotencyKeyReusedException;
import com.reservation.engine.idempotency.InvalidIdempotencyKeyException;
import com.reservation.engine.observability.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Centralised RFC 7807 mapping. Domain exceptions carry no HTTP knowledge; the mapping lives here only. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Map<Class<? extends DomainException>, HttpStatus> STATUS = new LinkedHashMap<>();

    static {
        STATUS.put(NotFoundException.class, HttpStatus.NOT_FOUND);
        STATUS.put(AccessForbiddenException.class, HttpStatus.FORBIDDEN);
        STATUS.put(InventoryUnavailableException.class, HttpStatus.CONFLICT);
        STATUS.put(InvalidStateTransitionException.class, HttpStatus.CONFLICT);
        STATUS.put(HoldExpiredException.class, HttpStatus.CONFLICT);
        STATUS.put(CancellationNotAllowedException.class, HttpStatus.CONFLICT);
        STATUS.put(ConcurrentModificationException.class, HttpStatus.CONFLICT);
        STATUS.put(IdempotencyInProgressException.class, HttpStatus.CONFLICT);
        STATUS.put(ItineraryMemberException.class, HttpStatus.CONFLICT);
        STATUS.put(InvalidRequestException.class, HttpStatus.UNPROCESSABLE_ENTITY);
        STATUS.put(IdempotencyKeyReusedException.class, HttpStatus.UNPROCESSABLE_ENTITY);
        STATUS.put(InvalidIdempotencyKeyException.class, HttpStatus.BAD_REQUEST);
        STATUS.put(PaymentDeclinedException.class, HttpStatus.PAYMENT_REQUIRED);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> handleDomain(DomainException ex) {
        HttpStatus status = STATUS.entrySet().stream()
                .filter(e -> e.getKey().isInstance(ex))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(HttpStatus.UNPROCESSABLE_ENTITY);
        if (status.is4xxClientError()) {
            log.info("request rejected code={} status={} detail={}", ex.code(), status.value(), ex.getMessage());
        }
        return problem(status, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Insufficient permissions");
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyFailureException ex) {
        log.info("concurrency conflict: {}", ex.getMessage());
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified concurrently; please retry");
    }

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(jakarta.validation.ConstraintViolationException ex) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request validation failed");
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", lastNode(v.getPropertyPath().toString()), "message", v.getMessage()))
                .toList();
        response.getBody().setProperty("errors", errors);
        return response;
    }

    private static String lastNode(String path) {
        int i = path.lastIndexOf('.');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.info("data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "Request conflicts with current state");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        ProblemDetail pd = ex.getBody();
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        pd.setDetail("Request validation failed");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail pd) {
            enrich(pd, pd.getProperties() != null && pd.getProperties().containsKey("code")
                    ? String.valueOf(pd.getProperties().get("code")) : "BAD_REQUEST");
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        enrich(pd, code);
        return ResponseEntity.status(status).body(pd);
    }

    private static void enrich(ProblemDetail pd, String code) {
        pd.setType(URI.create("urn:problem-type:reservation:" + code.toLowerCase()));
        pd.setProperty("code", code);
        pd.setProperty("correlationId", Correlation.currentId());
        pd.setProperty("timestamp", Instant.now().toString());
    }
}
