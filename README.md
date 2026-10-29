# reservation-engine

A booking backend that doesn't care what you're booking. Flight seats, hotel rooms, bus seats, event tickets,
appointments, rental cars all go through the same flow: **hold → confirm → cancel / expire**. Type-specific
rules (billing period, TTL, cancellation policy, attribute validation) plug in as Spring beans, so the core
never has an `if (type == HOTEL)` anywhere.

I built it to get properly hands-on with the stuff that usually goes wrong in booking systems: double booking
under concurrency, retries that charge twice, holds that never get released, and one hot row killing throughput
during a flash sale.

Java 21, Spring Boot 3.5, PostgreSQL 16, Flyway, Spring Security (JWT), Micrometer/Prometheus, Testcontainers.

## What it does

- Four inventory models: `CAPACITY` (GA tickets, bus seats), `EXCLUSIVE_UNIT` (seat 1A), `TIME_SLOT`
  (appointments) and `TIME_RANGE` (hotel nights, car rentals). Every one is protected by a DB constraint, not just
  app code. Overlapping hotel stays are blocked by a `btree_gist` `EXCLUDE` constraint.
- Holds expire on their own. A sweeper picks expired holds with `FOR UPDATE SKIP LOCKED`, so running several
  instances is fine.
- `Idempotency-Key` is required on every write. Same key + same body replays the stored response
  (`Idempotent-Replayed: true`). Same key + different body is a 422.
- Payment is called with no DB transaction open. An "unknown" outcome goes to `PAYMENT_PENDING` and a
  reconciliation job settles it later.
- Itineraries: flight + hotel + bus held, paid (one charge) and cancelled as one unit. If any item fails,
  nothing is held.
- Waitlist: join when sold out, and you're promoted to a HELD reservation automatically when something frees up.
- Flash-sale mode: a `CAPACITY` resource can be split into N bucket rows so holds don't all queue on one row lock.
- Transactional outbox for domain events, audit trail, optimistic locking (`@Version`) on reservations.
- RFC 7807 errors, paginated lists, JWT roles (`USER`/`ADMIN`), owner checks (403 on someone else's booking).
- Actuator liveness/readiness, Prometheus metrics (`reservation_*`), correlation id on every request/log line,
  JSON logs with the `json` profile, graceful shutdown.

Design details, schema and trade-offs are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Run it

You need Docker. Ports default to 7400 (app) and 55470 (Postgres); change them with `APP_PORT` / `PG_PORT`.

```bash
export JWT_SECRET="$(openssl rand -base64 48)"   # HS256, at least 32 bytes; the app won't start without it
docker compose up -d --build
curl localhost:7400/actuator/health/readiness
```

Then run the demo. It mints dev tokens with the same secret and walks through the main flows. The last step
waits for a hold to expire, so start the stack with a short TTL first:

```bash
HOLD_DEFAULT_TTL=PT20S EXPIRY_SWEEP_INTERVAL=PT2S docker compose up -d --build
./scripts/demo.sh
```

It prints the status codes it gets back ([full output from my run](docs/demo-output.txt)):

- 30 parallel holds on seat 1A → one 201, twenty-nine 409
- replaying a confirm with the same key → 200 with `replayed=true`; same key with a different body → 422
- overlapping hotel stay → 409, back-to-back stay (checkout = next check-in) → 201
- itinerary where one item is taken → 409 "nothing was held", bus seats untouched (38/40 stays 38/40)
- sold-out GA ticket → join waitlist → someone cancels → waitlist entry becomes `PROMOTED`
- a 20-second hotel hold left alone → `EXPIRED`, and the dates can be booked again

To run outside Docker: JDK 21 + Maven, a Postgres 14+ with `btree_gist`, then set `DB_URL`, `DB_USER`,
`DB_PASSWORD`, `JWT_SECRET` and `mvn -DskipTests package && java -jar target/reservation-engine-0.1.0-SNAPSHOT.jar`.

Dev tokens: `./scripts/dev-jwt.sh alice USER` / `./scripts/dev-jwt.sh admin-1 ADMIN` (needs `JWT_SECRET` in the env).
They're for local use only.

## API

Everything is under `/api/v1`, JSON in and out, errors are `application/problem+json`.

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

Fake payment tokens: `tok_visa` approves, `tok_decline` gives 402, `tok_unknown` / `tok_error` give 202
`PAYMENT_PENDING` (settled later by the reconciliation job). `scripts/demo.sh` doubles as a curl example
for every endpoint above.

<details>
<summary>Configuration</summary>

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

Relaxed binding works for anything else, e.g. `RESERVATION_HOLD_MAXTTL=PT30M`.
</details>

## Tests

```bash
mvn verify
```

79 unit tests (including 7 ArchUnit rules on package dependencies) and 95 integration tests. The integration
tests run against real Postgres, never H2: Testcontainers if Docker is reachable, otherwise whatever
`TEST_DB_URL` / `TEST_DB_USER` / `TEST_DB_PASSWORD` point at (`TEST_USE_DOCKER=false` skips the Docker attempt).
If no database is reachable they're skipped, not failed.

The interesting ones are the races: last seat, last unit, overlapping ranges, confirm vs expiry, cancel vs
confirm, the same idempotency key from many threads, itineraries locking the same items in opposite order,
and several waitlist promoters fighting over one release.

CI (GitHub Actions) runs `mvn verify`, builds the Docker image and runs a Trivy dependency scan that fails on
fixable HIGH/CRITICAL CVEs.

## Performance

There's a load benchmark (`scripts/run-benchmark.sh`) that boots the real app against Postgres and hammers the
service layer (no HTTP) from 50–100 threads. Single-machine dev numbers from my box, which was also running
other stuff, so they're noisy:

- one counter row: 418–1108 holds/s; with 16–64 bucket rows: 1734–3997 holds/s (2.9–6.4× depending on run)
- flash sale of 20 000 tickets at 100 threads: 16.8–27.5 s on one row vs 4.2–7.7 s with buckets,
  and **0 oversold, 0 deadlocks, 0 errors in all 18 runs**
- 3-item itinerary hold + cancel: 112–177/s with a single-row bus, 279–405/s with a sharded bus

Setup, method and every run: [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Adding a resource type

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

## Layout

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

## What's missing

Being honest about where this stops:

- Payments go through `FakePaymentAdapter`, and events go to `LoggingEventPublisher` (it just logs). Real PSP and
  Kafka/SQS adapters would need writing; the ports are there.
- Auth is a shared HS256 secret plus `scripts/dev-jwt.sh`. In real life you'd validate IdP tokens over JWKS.
- No REST API to update or deactivate a resource, or remove units/slots.
- Availability is a snapshot. A hold can still lose the race and get a 409, which is the real answer.
- Idempotent replays return the stored status and body but not headers like `Location`.
- No rate limiting. I'd expect a gateway to do that.
- Domain entities have JPA annotations on them, so it's not a "pure" hexagonal domain.
- Performance numbers are from one dev box, not a real deployment.
- Single currency per itinerary, no partial payments or partial refunds.

The longer list is at the end of [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).
