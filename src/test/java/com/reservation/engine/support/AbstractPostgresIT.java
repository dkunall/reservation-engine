package com.reservation.engine.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reservation.engine.config.ReservationProperties;
import com.reservation.engine.payment.FakePaymentAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for integration tests against a REAL PostgreSQL (see {@link TestDatabase}). Shares one Spring context,
 * truncates all tables before each test, and offers small HTTP helpers that go through the full
 * security filter chain with real HS256 tokens.
 */
@ExtendWith(RequiresPostgres.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    protected static final String ADMIN = "admin-1";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper mapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected FakePaymentAdapter fakePayment;
    @Autowired
    protected ReservationProperties props;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        TestDatabase.Target t = TestDatabase.get();
        if (t != null) {
            registry.add("spring.datasource.url", t::url);
            registry.add("spring.datasource.username", t::user);
            registry.add("spring.datasource.password", t::password);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
                TRUNCATE idempotency_record, outbox_event, payment_transaction, reservation_audit,
                         waitlist_signal, waitlist_entry,
                         range_allocation, slot_allocation, unit_allocation, capacity_allocation, capacity_bucket,
                         time_slot, inventory_unit, capacity_inventory, reservation, itinerary, resource
                RESTART IDENTITY CASCADE
                """);
        fakePayment.reset(FakePaymentAdapter.Mode.APPROVE);
    }

    // ---------- HTTP helpers ----------

    protected static String key() {
        return "key-" + UUID.randomUUID();
    }

    protected static Instant daysFromNow(int days) {
        return Instant.now().truncatedTo(ChronoUnit.HOURS).plus(Duration.ofDays(days));
    }

    protected String toJson(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        String s = result.andReturn().getResponse().getContentAsString();
        return s.isEmpty() ? mapper.nullNode() : mapper.readTree(s);
    }

    protected UUID createResource(String json) throws Exception {
        ResultActions r = mvc.perform(post("/api/v1/resources")
                        .header("Authorization", TestJwt.bearer(ADMIN, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated());
        return UUID.fromString(body(r).get("id").asText());
    }

    protected UUID createCapacityResource(int capacity, String price) throws Exception {
        return createResource("""
                {"type":"EVENT_GA","name":"Concert GA","price":{"amount":"%s","currency":"USD"},"inventory":{"capacity":%d}}
                """.formatted(price, capacity));
    }

    protected UUID createUnitResource(String type, String... units) throws Exception {
        return createResource("""
                {"type":"%s","name":"Unit resource","price":{"amount":"100.00","currency":"EUR"},"inventory":{"unitCodes":%s}}
                """.formatted(type, toJson(units)));
    }

    protected ResultActions hold(String user, String idemKey, Object body) throws Exception {
        return mvc.perform(post("/api/v1/reservations")
                .header("Authorization", TestJwt.bearer(user, "USER"))
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : toJson(body)));
    }

    protected UUID holdOk(String user, Object body) throws Exception {
        ResultActions r = hold(user, key(), body).andExpect(status().isCreated());
        return UUID.fromString(body(r).get("id").asText());
    }

    protected ResultActions confirm(String user, UUID id, String idemKey, String paymentToken) throws Exception {
        var req = post("/api/v1/reservations/{id}/confirm", id)
                .header("Authorization", TestJwt.bearer(user, "USER"))
                .header("Idempotency-Key", idemKey);
        if (paymentToken != null) {
            req.contentType(MediaType.APPLICATION_JSON).content("{\"paymentMethodToken\":\"" + paymentToken + "\"}");
        }
        return mvc.perform(req);
    }

    protected ResultActions cancel(String user, UUID id, String idemKey) throws Exception {
        return mvc.perform(post("/api/v1/reservations/{id}/cancel", id)
                .header("Authorization", TestJwt.bearer(user, "USER"))
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"test\"}"));
    }

    protected ResultActions getReservation(String user, UUID id) throws Exception {
        return mvc.perform(get("/api/v1/reservations/{id}", id).header("Authorization", TestJwt.bearer(user, "USER")));
    }

    protected String statusOf(UUID reservationId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE id = ?", String.class, reservationId);
    }

    protected void forceHoldExpiry(UUID reservationId) {
        jdbc.update("UPDATE reservation SET hold_expires_at = now() - interval '1 second' WHERE id = ?", reservationId);
    }

    protected int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    // ---------- concurrency helper ----------

    /** Runs n tasks released simultaneously by a latch; returns each task's result or thrown exception. */
    protected static <T> List<Object> runConcurrently(int n, IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
