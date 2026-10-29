package com.reservation.engine.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservation.engine.config.ReservationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private final IdempotencyRepository repo = mock(IdempotencyRepository.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final Instant now = Instant.parse("2030-01-01T00:00:00Z");
    private IdempotencyService service;
    private final IdempotencyService.Request req =
            new IdempotencyService.Request("alice", "key-12345678", "CREATE_RESERVATION", "hash-1");

    @BeforeEach
    void setUp() {
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new IdempotencyService(repo, new ObjectMapper(), tm, Clock.fixed(now, ZoneOffset.UTC),
                new ReservationProperties(null, null, null, null, null, null, null, List.of()),
                new com.reservation.engine.observability.ReservationMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void firstRequestExecutesAndStoresResponse() {
        when(repo.tryClaim(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.of(7L));
        AtomicInteger calls = new AtomicInteger();
        ResponseEntity<?> r = service.execute(req, IdempotencyService.Mode.ATOMIC, () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", "r1"));
        });
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(calls).hasValue(1);
        verify(repo).complete(eq(7L), eq(201), eq("{\"id\":\"r1\"}"), any());
    }

    @Test
    void completedKeyReplaysWithoutExecuting() {
        when(repo.tryClaim(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.reclaimStale(anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.find("alice", "key-12345678")).thenReturn(Optional.of(new IdempotencyRecord(7, "alice", "key-12345678",
                "CREATE_RESERVATION", "hash-1", IdempotencyRecord.Status.COMPLETED, 201, "{\"id\":\"r1\"}", now)));
        ResponseEntity<?> r = service.execute(req, IdempotencyService.Mode.ATOMIC, () -> {
            throw new AssertionError("must not execute");
        });
        assertThat(r.getStatusCode().value()).isEqualTo(201);
        assertThat(r.getHeaders().getFirst(IdempotencyService.REPLAY_HEADER)).isEqualTo("true");
        assertThat(r.getBody().toString()).contains("r1");
    }

    @Test
    void differentPayloadIsRejected() {
        when(repo.tryClaim(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.reclaimStale(anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.find("alice", "key-12345678")).thenReturn(Optional.of(new IdempotencyRecord(7, "alice", "key-12345678",
                "CREATE_RESERVATION", "OTHER", IdempotencyRecord.Status.COMPLETED, 201, "{}", now)));
        assertThatThrownBy(() -> service.execute(req, IdempotencyService.Mode.ATOMIC, () -> ResponseEntity.ok().build()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void inProgressIsRejected() {
        when(repo.tryClaim(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.reclaimStale(anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(repo.find("alice", "key-12345678")).thenReturn(Optional.of(new IdempotencyRecord(7, "alice", "key-12345678",
                "CREATE_RESERVATION", "hash-1", IdempotencyRecord.Status.IN_PROGRESS, null, null, now)));
        assertThatThrownBy(() -> service.execute(req, IdempotencyService.Mode.ATOMIC, () -> ResponseEntity.ok().build()))
                .isInstanceOf(IdempotencyInProgressException.class);
    }

    @Test
    void failureReleasesClaimSoRetryCanRun() {
        when(repo.tryClaim(anyString(), anyString(), anyString(), anyString(), any(), any())).thenReturn(Optional.of(9L));
        assertThatThrownBy(() -> service.execute(req, IdempotencyService.Mode.SEPARATE, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        verify(repo).release(9L);
        verify(repo, never()).complete(anyLong(), anyInt(), any(), any());
    }

    @Test
    void invalidKeysAreRejected() {
        assertThatThrownBy(() -> IdempotencyService.validateKey("short")).isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyService.validateKey("has spaces in it")).isInstanceOf(InvalidIdempotencyKeyException.class);
        assertThatThrownBy(() -> IdempotencyService.validateKey(null)).isInstanceOf(InvalidIdempotencyKeyException.class);
        IdempotencyService.validateKey("550e8400-e29b-41d4-a716-446655440000");
    }
}
