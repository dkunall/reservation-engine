# Reservation Engine

A generic, extensible **reservation engine** (backend only). One core workflow, **hold → confirm → cancel / expire**,
reserves any kind of resource: flights, hotel rooms, buses, trains, event tickets, vehicles, appointments, and new
types added later. Type-specific behaviour plugs in through extension contracts. The core never branches on resource type.

Stack: Java 21, Spring Boot 3.3, Spring Data JPA/Hibernate, PostgreSQL (btree_gist), Flyway, Spring Security
(JWT resource server, HS256), Actuator + Micrometer (Prometheus), Bean Validation, JUnit 5, Mockito, Testcontainers.

Beyond the core workflow it supports:

- **Itineraries**: atomic multi-resource booking (e.g. flight seat + hotel room + airport bus) held, paid
  (one payment for the total), cancelled and expired as one unit, with deterministic lock ordering.
- **Waitlist with auto-promotion**: join when sold out; released inventory (cancel / expiry) is turned into a HELD
  reservation for the next eligible waiter, exactly once, safe with any number of promoter instances.
- **Flash-sale mode**: CAPACITY inventory can be sharded into N bucket rows to remove the single hot row.
- **A reproducible load benchmark** with real numbers: see **[docs/PERFORMANCE.md](docs/PERFORMANCE.md)**.

See **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** for the low-level design, schema, concurrency approach and trade-offs.

---

## 1. Run locally

Prerequisites: JDK 21, Maven 3.9+, and a PostgreSQL 14+ server with the `btree_gist` extension available
(it ships with standard PostgreSQL / `postgresql-contrib`).

```bash
# 1) PostgreSQL (dev credentials reservation/reservation; also creates reservation_test)
docker compose up -d postgres

# 2) Secrets/config come from the environment. Never commit a real secret.
export DB_URL=jdbc:postgresql://localhost:5432/reservation
export DB_USER=reservation
export DB_PASSWORD=reservation
export JWT_SECRET="$(openssl rand -base64 48)"      # >= 32 bytes, HS256

# 3) Build & run (Flyway migrates the schema on start-up)
mvn -DskipTests package
java -jar target/reservation-engine-0.1.0-SNAPSHOT.jar
# JSON logs instead of key=value lines:  SPRING_PROFILES_ACTIVE=json java -jar ...
```

The service listens on `:8080`. `GET /actuator/health` is public. Metrics (`/actuator/metrics`, `/actuator/prometheus`) need the ADMIN role.

### Configuration (application.yml, overridable by env)

| Property | Env | Default | Meaning |
|---|---|---|---|
| `spring.datasource.url/username/password` | `DB_URL`, `DB_USER`, `DB_PASSWORD` | localhost/reservation | PostgreSQL |
| `spring.datasource.hikari.maximum-pool-size` | `DB_POOL_MAX` | 20 | HikariCP pool size (connections also get `lock_timeout=5s`) |
| `reservation.jwt.secret` | `JWT_SECRET` | **required** | HS256 key, at least 32 bytes. The app refuses to start without it |
| `reservation.jwt.issuer` | `JWT_ISSUER` | empty | Validates `iss` when set |
| `reservation.hold.default-ttl` | `HOLD_DEFAULT_TTL` | PT10M | Hold TTL when the type doesn't override it |
| `reservation.hold.max-ttl` | | PT1H | Upper bound on any TTL |
| `reservation.cancellation.cutoff-before-start` | | PT1H | Default policy: confirmed bookings can be cancelled (full refund) until this long before start |
| `reservation.expiry.sweep-interval` / `batch-size` | | PT15S / 100 | Expiry sweeper |
| `reservation.payment.reconcile-interval` / `reconcile-after` | | PT30S / PT1M | Reconciliation job for uncertain payments and pending refunds |
| `reservation.payment.fake-mode` / `fake-latency` | | APPROVE / 0 | Fake payment adapter (APPROVE, DECLINE, UNKNOWN, ERROR) |
| `reservation.idempotency.ttl` / `in-progress-timeout` | | PT24H / PT2M | Idempotency record lifetime / takeover of abandoned claims |
| `reservation.outbox.poll-interval` / `batch-size` | | PT2S / 100 | Outbox publisher |
| `reservation.scheduling.enabled` | `RESERVATION_SCHEDULING_ENABLED` | true | Turns all background jobs on or off |
| `reservation.custom-resource-types[]` | | PARKING_SPOT | Config-only resource types (see section 5) |
| `reservation.itinerary.max-items` / `lock-retry-attempts` | | 10 / 3 | Items per itinerary (DB max 20) / whole-transaction retries on deadlock or lock timeout |
| `reservation.waitlist.default-ttl` / `max-ttl` | | PT24H / P7D | How long an entry waits (also capped at the requested start time) |
| `reservation.waitlist.promote-interval` / `rescan-interval` / `expiry-interval` | | PT1S / PT1M / PT30S | Promoter, safety-net rescan, entry expiry |
| `reservation.waitlist.candidates-per-signal` / `signals-per-run` | | 20 / 100 | Promoter batch limits |

Spring relaxed binding also works for any property, e.g. `RESERVATION_HOLD_MAXTTL=PT30M`.

---

## 2. Generate a dev JWT

Tokens are HS256 JWTs. `sub` is the user id, and the `roles` claim holds an array such as `["USER"]` or `["ADMIN"]`.

```bash
export JWT_SECRET=...            # the same value the app runs with
ADMIN=$(./scripts/dev-jwt.sh admin-1 ADMIN)
ALICE=$(./scripts/dev-jwt.sh alice USER)
BOB=$(./scripts/dev-jwt.sh bob USER 600)      # optional 3rd arg: TTL seconds
```

`scripts/dev-jwt.sh` only needs `openssl`. It is for local development. In production, tokens come from your identity provider.
Swapping HMAC for JWKS means changing only the `JwtDecoder` bean in `SecurityConfig`.

---

## 3. API (all under `/api/v1`, JSON, errors are RFC 7807 `application/problem+json`)

| Method & path | Role | Notes |
|---|---|---|
| `POST /resources` | ADMIN | Create a resource and, optionally, its initial inventory → 201 |
| `GET /resources?type=&page=&size=` | any user | Paginated (size ≤ 100) |
| `GET /resources/{id}` | any user | |
| `POST /resources/{id}/inventory` | ADMIN | Add capacity, units or slots |
| `GET /resources/{id}/availability?from=&to=&quantity=` | any user | Non-locking snapshot |
| `GET /resource-types` | any user | Registered types and their inventory models |
| `POST /reservations` | user | **Idempotency-Key required**. Creates a HELD reservation → 201 |
| `GET /reservations/{id}` | owner/ADMIN | 403 for other users |
| `GET /reservations?status=&page=&size=` | user | The caller's own reservations, paginated |
| `POST /reservations/{id}/confirm` | owner/ADMIN | **Idempotency-Key**. 200 CONFIRMED, 202 PAYMENT_PENDING (payment outcome unknown), 402 declined |
| `POST /reservations/{id}/cancel` | owner/ADMIN | **Idempotency-Key**. 200 |
| `POST /itineraries` | user | **Idempotency-Key**. `{"items":[<reservation request>, ...]}` → 201 all items HELD, or 409/422 and **nothing** held |
| `GET /itineraries/{id}` | owner/ADMIN | Itinerary with its items |
| `GET /itineraries?status=&page=&size=` | user | Own itineraries (without items) |
| `POST /itineraries/{id}/confirm` | owner/ADMIN | **Idempotency-Key**. One charge for the total. 200 / 202 / 402 like reservations |
| `POST /itineraries/{id}/cancel` | owner/ADMIN | **Idempotency-Key**. All items or none (each item's cancellation policy); one refund |
| `POST /waitlist` | user | **Idempotency-Key**. Reservation request shape + optional `maxWaitMinutes` → 201 WAITING |
| `GET /waitlist/{id}`, `GET /waitlist?status=` | owner/ADMIN, user | Shows `promotedReservationId` once promoted |
| `POST /waitlist/{id}/cancel` | owner/ADMIN | **Idempotency-Key**. Leave the queue |

`POST /resources` and `POST /resources/{id}/inventory` accept `"buckets": N` (CAPACITY only, first provisioning) to
enable sharded counters. Reservations that belong to an itinerary return `itineraryId`, and their own
`/confirm` and `/cancel` answer **409 `MANAGED_BY_ITINERARY`**.

Status codes: 400 for malformed input, Bean Validation failures, or a missing or invalid Idempotency-Key; 401 for an
unauthenticated call; 403 for a missing role or another user's reservation; 404 for an unknown id; 409 for sold out,
an invalid state transition, an expired hold, a cancellation window that has closed, or the same key still in
progress; 422 for a request that is semantically invalid for the resource's model or a key reused with a different
payload; 402 for a declined payment.

### curl walkthrough

```bash
API=http://localhost:8080/api/v1
H='Content-Type: application/json'

# --- CAPACITY (event general admission) ---
GA=$(curl -s -X POST $API/resources -H "Authorization: Bearer $ADMIN" -H "$H" -d '{
  "type":"EVENT_GA","name":"Summer Festival GA",
  "price":{"amount":"49.99","currency":"USD"},"inventory":{"capacity":500}}' | jq -r .id)

RES=$(curl -s -X POST $API/reservations -H "Authorization: Bearer $ALICE" -H "$H" \
  -H "Idempotency-Key: $(uuidgen)" -d "{\"resourceId\":\"$GA\",\"quantity\":2}" | jq -r .id)

curl -s -X POST $API/reservations/$RES/confirm -H "Authorization: Bearer $ALICE" \
  -H "Idempotency-Key: $(uuidgen)" -H "$H" -d '{"paymentMethodToken":"tok_visa"}'
# fake adapter tokens: tok_decline -> 402, tok_unknown / tok_error -> 202 PAYMENT_PENDING (later reconciled)

curl -s -X POST $API/reservations/$RES/cancel -H "Authorization: Bearer $ALICE" \
  -H "Idempotency-Key: $(uuidgen)" -H "$H" -d '{"reason":"cannot attend"}'

# --- EXCLUSIVE_UNIT (flight seats) ---
FL=$(curl -s -X POST $API/resources -H "Authorization: Bearer $ADMIN" -H "$H" -d '{
  "type":"FLIGHT","name":"XY123 2026-12-01 BLR-DEL","attributes":{"departure":"2026-12-01T06:00:00Z"},
  "price":{"amount":"5200","currency":"INR"},"inventory":{"unitCodes":["1A","1B","1C","2A"]}}' | jq -r .id)
curl -s -X POST $API/reservations -H "Authorization: Bearer $ALICE" -H "$H" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"resourceId\":\"$FL\",\"unitCodes\":[\"1A\"]}"           # a specific seat
curl -s -X POST $API/reservations -H "Authorization: Bearer $BOB" -H "$H" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"resourceId\":\"$FL\",\"quantity\":2}"                    # any 2 seats (auto-assigned)

# --- TIME_RANGE (hotel room, nightly pricing) ---
HT=$(curl -s -X POST $API/resources -H "Authorization: Bearer $ADMIN" -H "$H" -d '{
  "type":"HOTEL_ROOM","name":"Deluxe King","price":{"amount":"120.00","currency":"EUR"},
  "inventory":{"unitCodes":["101","102"]}}' | jq -r .id)
curl -s "$API/resources/$HT/availability?from=2026-12-10T14:00:00Z&to=2026-12-12T11:00:00Z" -H "Authorization: Bearer $ALICE"
curl -s -X POST $API/reservations -H "Authorization: Bearer $ALICE" -H "$H" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"resourceId\":\"$HT\",\"startsAt\":\"2026-12-10T14:00:00Z\",\"endsAt\":\"2026-12-12T11:00:00Z\"}"

# --- TIME_SLOT (appointments) ---
DR=$(curl -s -X POST $API/resources -H "Authorization: Bearer $ADMIN" -H "$H" -d '{
  "type":"APPOINTMENT","name":"Dr. Rao - consultation","price":{"amount":"800","currency":"INR"},
  "inventory":{"slots":[{"startsAt":"2026-12-01T09:00:00Z","endsAt":"2026-12-01T09:30:00Z","capacity":1}]}}' | jq -r .id)
curl -s -X POST $API/reservations -H "Authorization: Bearer $ALICE" -H "$H" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"resourceId\":\"$DR\",\"startsAt\":\"2026-12-01T09:00:00Z\"}"   # or "slotId": "<id from availability>"

# own reservations, paginated
curl -s "$API/reservations?page=0&size=20" -H "Authorization: Bearer $ALICE"

# --- ITINERARY: flight seat + hotel room + airport bus, all or nothing (items must share one currency) ---
mk() { curl -s -X POST $API/resources -H "Authorization: Bearer $ADMIN" -H "$H" -d "$1" | jq -r .id; }
F2=$(mk '{"type":"FLIGHT","name":"6E-201","price":{"amount":"4000","currency":"INR"},"inventory":{"unitCodes":["1A","1B"]}}')
H2=$(mk '{"type":"HOTEL_ROOM","name":"Goa Resort","price":{"amount":"3000","currency":"INR"},"inventory":{"unitCodes":["101"]}}')
B2=$(mk '{"type":"BUS","name":"Airport shuttle","price":{"amount":"250","currency":"INR"},"inventory":{"capacity":40}}')
IT=$(curl -s -X POST $API/itineraries -H "Authorization: Bearer $ALICE" -H "$H" -H "Idempotency-Key: $(uuidgen)" -d "{
  \"items\":[{\"resourceId\":\"$F2\",\"unitCodes\":[\"1A\"]},
             {\"resourceId\":\"$H2\",\"startsAt\":\"2026-12-01T14:00:00Z\",\"endsAt\":\"2026-12-03T11:00:00Z\"},
             {\"resourceId\":\"$B2\",\"quantity\":2}]}" | jq -r .id)
curl -s -X POST $API/itineraries/$IT/confirm -H "Authorization: Bearer $ALICE" -H "Idempotency-Key: $(uuidgen)"

# --- WAITLIST: join when sold out, get auto-promoted to a HELD reservation on release ---
W=$(curl -s -X POST $API/waitlist -H "Authorization: Bearer $BOB" -H "$H" -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"resourceId\":\"$GA\",\"quantity\":1,\"maxWaitMinutes\":120}" | jq -r .id)
curl -s $API/waitlist/$W -H "Authorization: Bearer $BOB"   # status WAITING -> PROMOTED, promotedReservationId

# --- FLASH SALE: sharded capacity ---
curl -s -X POST $API/resources -H "Authorization: Bearer $ADMIN" -H "$H" -d '{
  "type":"EVENT_GA","name":"Flash sale","price":{"amount":"1.00","currency":"USD"},
  "inventory":{"capacity":20000,"buckets":16}}'
```

Retrying a request with the same `Idempotency-Key` and the same body returns the stored response with the
`Idempotent-Replayed: true` header and causes no second effect. A different body with the same key gets a 422.
A retry that arrives while the first request is still running gets a 409.

---

## 4. Tests

```bash
mvn verify            # unit tests (surefire, *Test) + integration tests (failsafe, *IT)
mvn test              # unit tests only
mvn -DskipTests package
```

Integration tests always run against **real PostgreSQL** (never H2). `TestDatabase` picks the database in this order:

1. **Testcontainers** (`postgres:16-alpine`) if a Docker daemon is reachable (set `TEST_USE_DOCKER=false` to opt out)
2. **External DB** from `TEST_DB_URL` / `TEST_DB_USER` / `TEST_DB_PASSWORD` (env or `-D` system properties)
3. `jdbc:postgresql://localhost:5432/reservation_test` with `postgres/postgres`, if reachable

If none of these is reachable, the integration tests are **skipped** (JUnit assumptions) rather than failed. Example
against the compose database:

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/reservation_test TEST_DB_USER=reservation TEST_DB_PASSWORD=reservation mvn verify
```

New in this version: `ItineraryIT` (atomicity incl. partial-failure rollback, idempotent retry, single payment,
decline/unknown/reconciliation, unit cancel + refund, unit expiry, item-endpoint guard, two races with opposite item
orders that must produce only 201/409 and exact winner counts), `WaitlistIT` (promotion on cancel and on expiry,
FIFO, next-eligible, specific-seat waiters, expiry/cancel never promoted, idempotent join, 5 concurrent releases ×
6 concurrent promoters ⇒ exactly 5 promotions, 8 promoters on one release ⇒ exactly 1), `ShardedCapacityIT`
(provisioning split, source-bucket release, splits across buckets, DB CHECK and sum trigger, 28 threads with random
quantities never oversell and lose nothing), `LockTimeoutTranslationIT` (a PostgreSQL lock timeout from a JDBC
statement must surface as 409 / `PessimisticLockingFailureException`, not 500; regression test for a defect the
benchmark found), plus unit tests for lock ordering, the lock-conflict retry helper, itinerary/waitlist lifecycles and bucket shares.

Coverage: state machine, registries, policies, hashing, and idempotency decisions (unit); the full HTTP flows with
real HS256 JWTs, including 400/401/403/404/409/422/402 (integration); each inventory model plus its DB constraints;
the background jobs; the extension types; and races (last capacity, unit, slot or overlapping range, any-unit
assignment, confirm vs expiry, cancel vs confirm, concurrent same Idempotency-Key, conflicting payloads, and
rollback of partial multi-unit holds).

---

## 5. Adding a new resource type

The core workflow (`ReservationService`, `ConfirmationService`, `ExpirySweeper`, ...) is never edited.

**a) Config only.** This works when an existing inventory model fits and you don't need custom rules:

```yaml
reservation:
  custom-resource-types:
    - code: PARKING_SPOT        # upper-case code
      inventory-model: TIME_RANGE
      hold-ttl: PT5M            # optional
      billing-period: PT1H      # optional (TIME_RANGE pricing period)
```

**b) Code extension.** Use this when you need validation, a custom TTL, billing, or a cancellation policy. Add a
Spring bean implementing `ResourceTypeDefinition`. The working example is
`extensions/vehicle/VehicleRentalResourceType` (VEHICLE): it reuses TIME_RANGE, bills hourly, enforces a 1 h minimum
and a 30-day maximum rental, requires `make/model/seats` attributes, and has its own cancellation policy (free until
24 h before pick-up, 50 % after). Nothing else changed. `VehicleRentalExtensionIT` proves it end to end.

```java
@Component
public class BoatCharterResourceType implements ResourceTypeDefinition {
    public String code() { return "BOAT"; }
    public String inventoryModel() { return InventoryModels.TIME_RANGE; }
    public Duration billingPeriod() { return Duration.ofHours(4); }
    public void validateAttributes(Map<String, Object> a) { /* throw InvalidRequestException */ }
}
```

**c) New inventory model.** Use this when no model fits, for example seat maps with adjacency. Implement
`InventoryStrategy` as a bean, add a Flyway migration (next free version: `V13__...sql`) for its own tables, and add a model code
constant. The `InventoryStrategyRegistry` resolves it by `model()`. Startup fails fast if a type refers to an unknown
model or if two strategies or types collide.

---

## 6. Project layout

```
src/main/java/com/reservation/engine
├── api/            REST controllers, DTOs, RFC 7807 handler
├── application/    use cases: HoldExecutor, ReservationService, ConfirmationService, PaymentFinalizer, ExpirySweeper,
│                   PaymentReconciliationJob, ItineraryService, ItineraryConfirmationService,
│                   ItineraryPaymentFinalizer, ItineraryJobs, event recorders (audit + outbox)
├── waitlist/       WaitlistEntry, WaitlistService, WaitlistSignals (release listener), WaitlistPromoter
├── domain/         Reservation aggregate, state machine, Money, policies, domain exceptions
├── resourcetype/   ResourceTypeDefinition SPI + registry
├── inventory/      spi (InventoryStrategy, registry) + capacity / unit / slot / range implementations
├── payment/        PaymentPort SPI, fake adapter, payment_transaction entity
├── idempotency/    Idempotency-Key store and service
├── outbox/         outbox entity, writer, publisher job, EventPublisher SPI
├── persistence/    JPA repositories, audit entity, JDBC helpers (savepoints, time)
├── security/       JWT resource server config, 401/403 problem handlers
├── observability/  correlation-id filter, metrics, health
├── config/         properties, wiring, built-in resource types, scheduling
└── extensions/     vehicle/ - extensibility demo
src/main/resources/db/migration   V1..V12 Flyway migrations
src/test/java/.../bench/LoadBenchmark.java   load benchmark (scripts/run-benchmark.sh)
```

## 7. Load benchmark

```bash
scripts/run-benchmark.sh                  # needs local PostgreSQL on :5432 (postgres/postgres), creates reservation_bench
POOLS="20 50" scripts/run-benchmark.sh    # repeat per Hikari pool size
# equivalent: mvn -B -Pbenchmark test-compile exec:exec -Dbench.pool=20 -Dbench.threads=50,100,200
```

Headline numbers (service level, not HTTP; 8 vCPU / 16 GB VM; PostgreSQL on the same machine; pool 20; medians of 3×10 s runs):

| scenario | result |
|---|---|
| CAPACITY holds, single row vs sharded, 50/100/200 threads | ~1.4 k/s vs ~6–7 k/s (4.4–5.0×); single-row p99 1.4 s at 200 threads vs 0.24 s sharded |
| Flash-sale sell-out, 20 000 units, 200 threads | 13.5–14.3 s single-row vs 3.1–3.6 s sharded; oversold 0 in every run |
| 3-item itinerary (hold + cancel) | 134–175/s baseline → ~240/s after lock-order tuning → ~600/s with a sharded BUS |
| TIME_RANGE any-room, overlapping dates | ~550/s → ~1.1 k/s after the SKIP LOCKED first pass |

Method, machine, all raw numbers, the pool-50 comparison (no better), disturbed runs and limitations:
[docs/PERFORMANCE.md](docs/PERFORMANCE.md). Raw result files: `docs/benchmark-results/`.
