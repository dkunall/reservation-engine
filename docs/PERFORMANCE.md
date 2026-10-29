# Performance

**These are single-machine dev numbers.** They come from two runs of the benchmark in this repo on 29 Oct 2026
(run A at about 23:25 IST, run B at about 23:39 IST) on one shared dev box. They are not production numbers and
they're noisy, so treat them as "how the designs compare", not "how fast this is". Raw output:
[run A](benchmark-results/2026-10-29-run-a-boot-3.3.5.md), [run B](benchmark-results/2026-10-29-run-b-boot-3.5.16.md).

## Setup

| | |
|---|---|
| Machine | 8 vCPU Intel Xeon cloud VM, 16 GB RAM |
| Database | PostgreSQL 16.15 (`postgres:16-alpine` in Docker) on the same box, stock settings (`shared_buffers=128MB`, `synchronous_commit=on`, `fsync=on`) |
| JVM | OpenJDK 21.0.13, 2 GB heap |
| App | Hikari pool 20 (the default), `lock_timeout=5s` |
| Neighbours | The load generator, the app and Postgres share the 8 vCPUs with containers from other projects. Run B overlapped with other heavy builds (load average 19–35), which is why it's mostly slower than run A. |
| Code | Run A: Spring Boot 3.3.5. Run B: Spring Boot 3.5.16 (current `main`). The SQL and locking paths are identical. |

## 2. Method

- **Harness:** `src/test/java/com/reservation/engine/bench/LoadBenchmark.java` boots the real Spring application
  (same beans, same Flyway schema, same transactions) against a dedicated `reservation_bench` database. Scheduling is off.
- **What is measured:** the **application service layer** (`ReservationService.hold`, `ItineraryService.hold` etc.).
  N platform threads call it in closed loops. HTTP, JSON, JWT and the idempotency layer are **not** included, so these
  numbers are an upper bound for the HTTP API.
- **Runs:** each configuration does a 5 s warm-up run (discarded), then **3 measured runs of 10 s**. Before each run
  the tables are truncated and `VACUUM ANALYZE`d.
- **Metrics per run:**
  - successful ops/s and attempts/s;
  - **409 %**: share of attempts rejected as unavailable;
  - latency p50/p95/p99/max of successful operations, from client-side `System.nanoTime`;
  - **avg/max lock waiters**: sessions in `pg_stat_activity` with `wait_event_type='Lock'`, sampled every 100 ms;
  - **avg pool waiters**: Hikari threads waiting for a connection;
  - **deadlocks**: delta of `pg_stat_database.deadlocks`;
  - **tx retries**: whole-transaction retries by `LockConflictRetry`;
  - **errors**: any other exception.
- **"median" rows:** median of the 3 runs' ops/s and median of the 3 runs' p99.

### Reproduce

```bash
docker run -d --name reservation-pg-bench -p 55472:5432 -e POSTGRES_PASSWORD=postgres postgres:16-alpine
THREADS=50,100 BENCH_DB_URL=jdbc:postgresql://localhost:55472/reservation_bench scripts/run-benchmark.sh
```

It takes about 10 minutes. Results go to stdout and `target/benchmark/results-pool<N>-<timestamp>.md`.

### Scenarios

| | Scenario | Workload |
|---|---|---|
| (a) | CAPACITY throughput | One EVENT_GA resource with 50 M capacity (never sells out), qty 1 holds. Single counter row vs 16 vs 64 buckets, at 50/100 threads. |
| (a2) | Flash-sale sell-out | 20 000 units, 100 threads, holds until sold out. Checks that `sold = Σ reserved = Σ ACTIVE allocations = 20 000`. |
| (b) | Itinerary | 3-item itinerary per op: BUS (CAPACITY, unlimited) + FLIGHT (any of 60 seats) + HOTEL (any of 60 rooms, same dates). Each hold is followed by a cancel of the whole itinerary. Item order alternates between requests to exercise lock ordering. The BUS is either a single row or 16 buckets. Max 100 threads. |
| (c) | TIME_RANGE contention | 40 hotel rooms; each request wants any room for 2 nights starting on day 30 + (0..2), so periods overlap heavily. Each hold is followed by a cancel. Max 100 threads. |

## Results

Medians of 3 × 10 s runs. Format: **run A / run B**.

### (a) CAPACITY holds/s

| threads | single row | 16 buckets | 64 buckets |
|---|---|---|---|
| 50 | 848 / 418 (p99 302 / 455 ms) | 2458 / 1734 (p99 118 / 131 ms) | 3851 / 2691 (p99 79 / 99 ms) |
| 100 | 1108 / 731 (p99 571 / 795 ms) | 3997 / 3165 (p99 175 / 226 ms) | 3499 / 2454 (p99 206 / 281 ms) |

Sharding gave 2.9–6.4× more holds/s depending on run and thread count. The single row has ~17 sessions
waiting on its lock at any moment; with buckets that drops to about 1. 64 buckets wasn't consistently better than
16 at 100 threads (the individual runs swing a lot, e.g. 4505 / 3499 / 2057 in run A).

### (a2) Flash sale: 20 000 units, 100 threads, until sold out

| layout | sell-out time, run A | sell-out time, run B |
|---|---|---|
| single row | 16.8–21.1 s | 20.7–27.5 s |
| 16 buckets | 4.4–4.9 s | 6.0–7.7 s |
| 64 buckets | 4.2–4.5 s | 5.9–7.1 s |

The point of this one: **oversold 0, deadlocks 0, errors 0 in all 18 runs**. After each run the benchmark checks
that units sold = sum of `reserved` = count of active allocations = 20 000.

### (b) 3-item itinerary (bus + flight seat + hotel room), hold then cancel, ops/s

| threads | bus as single row | bus with 16 buckets |
|---|---|---|
| 50 | 121 / 119 (p99 851 / 716 ms) | 405 / 335 (p99 293 / 349 ms) |
| 100 | 177 / 112 (p99 1179 / 1630 ms) | 279 / 314 (p99 1059 / 787 ms) |

Each op is two multi-row transactions touching three resources, so it's much slower than a single hold.
The shared bus counter is the bottleneck; sharding it gave 1.6–3.3× more itineraries/s.

### (c) Hotel, any room, overlapping dates, hold then cancel, ops/s

| threads | ops/s | p99 |
|---|---|---|
| 50 | 674 / 876 | 187 / 120 ms |
| 100 | 640 / 655 | 500 / 452 ms |

## What these numbers don't tell you

- No HTTP, JSON, JWT or idempotency-table cost. The HTTP API will be slower.
- One Postgres with default settings on the same box as the load generator. A real setup would have the DB on its
  own machine with tuned memory and disks, and would usually be limited by something else.
- Only pool size 20 was measured in these runs.
- Two runs on a noisy shared machine. Run-to-run spread within a single configuration is up to ~2×.
