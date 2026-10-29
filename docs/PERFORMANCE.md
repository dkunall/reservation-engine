# Performance: load benchmark results

All numbers on this page come from runs of the benchmark in this repository on the machine below, on
**20 Oct 2026, between about 21:50 and 22:40 IST**. Nothing here is estimated or taken from somewhere else. Raw output files are
in [`docs/benchmark-results/`](benchmark-results/). The benchmark is noisy (see section 7), so read the medians
together with the per-run numbers.

## 1. Setup

| | |
|---|---|
| CPU | 8 vCPU, Intel Xeon (cloud VM, shared host) |
| RAM | 16 GB (`MemTotal 16386368 kB`; ~15 GiB usable) |
| JVM | OpenJDK 21.0.12, `-Xms2g -Xmx2g` |
| Database | PostgreSQL 17.11 on the **same machine** (localhost:5432); `max_connections=100`, `shared_buffers=128MB`, `synchronous_commit=on`, `fsync=on` (stock settings, no tuning) |
| App settings | Hikari pool 20 (application default) unless stated; `lock_timeout=5s`; Hikari `connection-timeout=5s` |

The load generator, the Spring application and PostgreSQL all share the same 8 vCPUs.

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
# PostgreSQL must be running on localhost:5432 with postgres/postgres (local throwaway creds)
scripts/run-benchmark.sh                                    # full suite, pool 20 (~15 min)
POOLS="20 50" scripts/run-benchmark.sh                      # repeat per pool size
SCENARIOS=itinerary,range THREADS=50,100 scripts/run-benchmark.sh
# same thing without the script:
mvn -B -Pbenchmark test-compile exec:exec -Dbench.pool=20 -Dbench.threads=50,100,200 \
    -Dbench.scenarios=capacity,sellout,itinerary,range
```

Results go to stdout and `target/benchmark/results-pool<N>-<timestamp>.md`.

### Scenarios

| | Scenario | Workload |
|---|---|---|
| (a) | CAPACITY throughput | One EVENT_GA resource with 50 M capacity (never sells out), qty 1 holds. Single counter row vs 16 vs 64 buckets, at 50/100/200 threads. |
| (a2) | Flash-sale sell-out | 20 000 units, 200 threads, holds until sold out. Checks that `sold = Σ reserved = Σ ACTIVE allocations = 20 000`. |
| (b) | Itinerary | 3-item itinerary per op: BUS (CAPACITY, unlimited) + FLIGHT (any of 60 seats) + HOTEL (any of 60 rooms, same dates). Each hold is followed by a cancel of the whole itinerary. Item order alternates between requests to exercise lock ordering. The BUS is either a single row or 16 buckets. Max 100 threads. |
| (c) | TIME_RANGE contention | 40 hotel rooms; each request wants any room for 2 nights starting on day 30 + (0..2), so periods overlap heavily. Each hold is followed by a cancel. Max 100 threads. |

## 3. (a) Single row vs sharded CAPACITY

Raw file: `benchmark-results/01-baseline-pool20.md`. Later code changes (sections 4–6) do not touch the
single-resource CAPACITY path, so these numbers were not re-measured.

### Pool 20: median ok ops/s and median p99 (ms)

| threads | single-row | sharded-16 | sharded-64 | speed-up (best sharded / single) |
|---|---|---|---|---|
| 50 | 1 372 / 130.85 | 4 167 / 77.35 | 6 925 / 40.17 | 5.0× |
| 100 | 1 498 / 384.80 | 6 127 / 107.92 | 6 621 / 103.49 | 4.4× |
| 200 | 1 379 / 1 375.17 | 6 163 / 241.29 | 5 960 / 246.39 | 4.5× |

### Per-run ops/s

| threads | single-row | sharded-16 | sharded-64 |
|---|---|---|---|
| 50 | 1372, 1408, 1290 | 2934, 4167, 6536 | 6655, 6925, 6968 |
| 100 | 1436, 1498, 1543 | 6093, 6127, 6468 | 6621, 4889, 7034 |
| 200 | 1367, 1379, 1447 | 2963, 6163, 6487 | 3113, 6638, 5960 |

- **Lock waiters:**
  - single-row has 15.9–17.0 average lock waiters, out of at most 19 other connections, so practically the whole pool
    queues on one row;
  - sharded-16 has 0.5–2.9;
  - sharded-64 has ~0.
- **Pool waiters** are 30 / 80 / 180 at 50 / 100 / 200 threads. Pool 20 caps DB concurrency at 20 in every
  configuration, so higher thread counts mostly add queueing time in the pool, not DB work.
- **Single row** p50 at 200 threads was only 16–17 ms, but p99 was 1.2–1.6 s and the max 3.8–4.3 s. The single row is
  unfair under contention: lock queueing plus pool queueing make the tail long.
- Deadlocks 0, tx retries 0 and errors 0 in all 27 runs.

### (a2) Flash-sale sell-out: 20 000 units, 200 threads, pool 20

| layout | run 1 | run 2 | run 3 | oversold |
|---|---|---|---|---|
| single-row | 13.52 s (1479/s) | 13.96 s (1433/s) | 14.29 s (1399/s) | 0 |
| sharded-16 | 3.36 s (5951/s) | 3.62 s (5528/s) | 3.34 s (5981/s) | 0 |
| sharded-64 | 3.34 s (5980/s) | 3.13 s (6391/s) | 3.18 s (6284/s) | 0 |

- Every run ended with exactly 20 000 sold, `Σ reserved = 20 000` and `Σ ACTIVE allocations = 20 000`. No oversell and
  no lost unit.
- In this baseline run, two benchmark worker threads in the single-row runs died with a Hikari 5 s connection timeout
  (`waiting=179`). The harness did not catch that exception type at the time; it now counts it in an `errors` column.
  The invariant still held because the other 198 threads kept selling.

**Re-run with error counting** (file 08, pool 20, 22:37 IST). Every run had errors 0, oversold 0 and invariant OK.

| layout | run 1 | run 2 | run 3 |
|---|---|---|---|
| single-row | 20.54 s | 15.06 s | 14.40 s |
| sharded-16 | 3.28 s | 3.47 s | 5.61 s |
| sharded-64 | 3.78 s | 3.27 s | 3.47 s |

These are consistent with the baseline, apart from one slow run each for single-row and sharded-16.

### Does a bigger pool help? Pool 50 vs pool 20 (CAPACITY)

Raw file: `benchmark-results/05-full-suite-pool50.md`. Median ok ops/s, with average lock waiters in brackets:

| threads | single-row 20 → 50 | sharded-16 20 → 50 | sharded-64 20 → 50 |
|---|---|---|---|
| 50 | 1372 (16) → 1187 (36) | 4167 (2) → 4515 (23) | 6925 (0) → 6001 (0) |
| 100 | 1498 (16) → 1183 (37) | 6127 (1) → 4572 (24) | 6621 (0) → 6279 (0) |
| 200 | 1379 (16) → 1142 (37) | 6163 (1) → 4271 (24) | 5960 (0) → 6307 (0) |

Sell-out at pool 50:

| layout | sell-out times |
|---|---|
| single-row | 17.46 / 16.42 / 16.48 s |
| sharded-16 | 4.68 / 4.90 / 4.78 s |
| sharded-64 | 3.42 / 3.55 / 3.39 s |

Oversold was 0 in every run. As in the pool-20 baseline, a few sell-out worker threads died on Hikari connection
timeouts at 200 threads, before the harness counted that error type. The invariant still held.

**No, a bigger pool does not help.**

- **Single row:** more connections only make the queue on the row longer (~37 waiters). Throughput drops by about 15–20 %.
- **sharded-16:** 50 concurrent transactions on 16 buckets often find all buckets busy, so they fall to the blocking
  path (~24 lock waiters). That costs about 25 % at ≥100 threads.
- **sharded-64:** unchanged at about 6.0–6.3 k/s.
- **Default:** the pool stays at 20. The rule that falls out is to use at least as many buckets as concurrent DB
  transactions.

## 4. (b) Itinerary (3 items, hold + cancel), pool 20

All itinerary runs used pool 20. Median ok ops/s, median p99 in brackets.

| version | raw file | 50 threads | 100 threads | lock waiters | errors | notes |
|---|---|---|---|---|---|---|
| baseline | 01 | 175 (880 ms) | 134 (2246 ms) | 17–19 | 1, 0, **47** at 100 threads | per run: 175/183/122 and 98/141/134 |
| after TIME_RANGE change (section 5) | 02 | **101** (663 ms) | 259 (1483 ms) | 16–18 | 1 | per run at 100: 95/341/259. The 50-thread number got worse. |
| after `lockOrderRank` (counters last) | 03 | 218 (375 ms) | 245 (1287 ms) | ~17 | 0 | per run: 235/218/194 and 224/252/245 |
| final, bus single-row | 06 | 243 (426 ms) | 240 (1473 ms) | ~17 | 0 | per run: 286/222/243 and 240/229/253 |
| final, bus **sharded-16** | 06 | **596** (228 ms) | **601** (524 ms) | 1.3–3.8 | 0 | per run: 570/596/618 and 601/835/375 |

Earlier run of the same two variants (file 04): single-row 223 / 226; sharded-16 589 / 545.

Errors and retries:

- The 47 errors in the baseline were Hikari 5 s connection timeouts (pool starvation). Each itinerary transaction
  queued on the hot BUS row while holding a connection.
- From the `lockOrderRank` change onwards, pool 20 showed 0 errors, 0 deadlocks and 0 tx retries.

What the itinerary numbers say:

1. **The bottleneck is the single BUS counter row.**
   - Every itinerary updates it, so about 17 of the 19 other connections wait on it.
   - Making the BUS a sharded resource (16 buckets) gives **2.4–2.5× more itineraries/s**, and p99 drops to between
     a half and a third.
   - So the multi-resource transaction itself is not the limit; any shared hot row in it is.
2. **Lock ordering matters for throughput, not just for deadlock freedom.**
   - At first, items were sorted only by resource id, so the BUS row was sometimes locked first and held while the
     seat and room searches ran.
   - `InventoryStrategy.lockOrderRank()` makes counter-style strategies (CAPACITY, TIME_SLOT) lock **last**. The
     order is still global and deterministic, so it is still deadlock-free.
   - Measured effect: 101 → 218 ops/s at 50 threads, and 0 errors instead of 1–47 at 100 threads.
3. **Deadlocks were 0 in every run**, even though half the requests list their items in the opposite order. That is
   the point of the deterministic ordering.
4. **409 % at 100 threads (sharded BUS, 4–21 %) is real unavailability, not a failure.**
   - Each hold is cancelled right after, but with 100 threads and 60 seats or rooms, holds sometimes run ahead of
     cancels.
   - The rejected itineraries left nothing held: atomicity is covered by `ItineraryIT`, not by this benchmark.

## 5. (c) TIME_RANGE contention: before and after the SKIP LOCKED first pass, pool 20

**Problem seen in the baseline:**

- An "any room" request scanned candidate rooms in id order with a blocking `FOR UPDATE`.
- Every concurrent request queued on the same lowest-id free room: a convoy with ~12–13 average lock waiters.

**Change:**

- `TimeRangeInventoryStrategy.allocateAny` first tries candidates with `FOR UPDATE SKIP LOCKED` inside a savepoint.
- Only if that finds nothing does it fall back to the original blocking pass in ascending order, so a room that is
  only *temporarily* locked is still found. Correctness tests are unchanged and green.

| version | raw file | 50 threads ops/s (p99) | 100 threads ops/s (p99) | avg lock waiters | 409 % at 100 threads |
|---|---|---|---|---|---|
| before | 01 | 545 (277 ms); per run 522 / 545 / 547 | 555 (705 ms); per run 555 / 625 / 543 | 11–13 | 1.9 / 4.2 / 3.3 |
| after | 02 | 1127 (158 ms); per run 1080 / 1127 / 1128 | 1109 (360 ms); per run 2536 / 1079 / 1109 | 0.1–3 | 4.2 / 4.8 / 4.9 |
| final re-run | 06 | 1137 (163 ms); per run 1120 / 1143 / 1137 | 1083 (515 ms); per run 2046 / 1083 / 983 | 0.1–3 | 3.4 / 1.7 / 2.2 |

**Result:** about **2× throughput** and roughly half the p99, measured at both thread counts.

**Costs:**

- The 409 rate at 100 threads did not improve; it is noise-level, 2–5 %. Those 409s are genuine "no room free for
  that period at that instant", because 100 threads compete for 40 rooms with hold-then-cancel.
- The extra statements made the *itinerary* scenario slower (section 4). That regression is what led to the
  `lockOrderRank` change.

## 6. A correctness defect the benchmark found: lock timeouts from JdbcTemplate became HTTP 500

**What happened:**

- In the first pool-50 run (file 05), two itinerary runs at 100 threads reported 4 and 3 errors of type
  `UncategorizedSQLException ... SQL state [55P03] ... canceling statement due to lock timeout`.
- **Cause:** since Spring 6, `JdbcTemplate` defaults to `SQLExceptionSubclassTranslator`. The PostgreSQL driver does
  not throw the JDBC 4 subclasses, so a 55P03 from the JDBC-based inventory strategies was not translated to
  `CannotAcquireLockException`.
- **Effect:** the API answered **500** instead of **409 CONCURRENT_MODIFICATION**, and `LockConflictRetry` did not
  retry the itinerary transaction. The JPA path was already translated correctly.

**Fix:**

- `config/JdbcExceptionTranslationConfig` puts the error-code translator, whose bundled PostgreSQL mapping covers
  55P03 and 40P01, on the auto-configured `JdbcTemplate`.
- **Regression test:** `LockTimeoutTranslationIT` (2 tests). It **fails without the fix**, with
  "Status expected:<409> but was:<500>"; this was checked by temporarily removing the class. It passes with the fix.

**After the fix:** the clean pool-50 re-run (file 09, itinerary, single-row BUS, 100 threads) recorded **12, 4 and 10
whole-transaction retries**, with **0 errors** and 0 deadlocks. This is the first time the retry path was **observed
firing under load**. The retried transactions finished as successes or as normal 409s. The p99/max of 5.3–7 s in those
runs is the 5 s lock timeout plus the retry.

## 7. Disturbed runs (host pauses), and why some pool-50 numbers are excluded

**Two whole-VM freezes:**

- Hikari's housekeeper logged "Thread starvation or clock leap detected" twice during the pool-50 runs:
  - a 2 min 34 s delta at 22:20:57 IST, in file 05, range scenario at 100 threads, run 2;
  - a 3 min 16 s delta at 22:32:05 IST, in file 07, itinerary single-row bus at 100 threads, runs 2–3.
- During those windows, connection waits of 126 s and 193 s were logged even though the pool timeout is 5 s. The whole
  VM (JVM and PostgreSQL) was not scheduled.
- Both coincided with the agent session that drove the runs being suspended or idle, which points to the host pausing
  the VM. That is likely, but not proven.

**Excluded runs:**

- file 05, range at 100 threads, run 2: 77 ops/s, 51 errors;
- file 07, itinerary single-row at 100 threads, runs 2 and 3: 4 and 60 ops/s, 24 and 31 errors, 40 and 35 retries.

These are not application behaviour, and the affected configuration was re-run (section 8). Smaller disturbances
can't be ruled out in other runs: run-to-run spread like 2934 / 4167 / 6536 for sharded-16 at 50 threads is larger
than lock or pool waits explain.

## 8. Pool 50 for the itinerary and range scenarios

Median ok ops/s, median p99 in brackets. Pool 20 values are from file 06.

| scenario | threads | pool 20 | pool 50 | pool 50 source |
|---|---|---|---|---|
| itinerary, bus single-row | 50 | 243 (426 ms) | 250 (462 ms); earlier run 257 | 07; 05 |
| itinerary, bus sharded-16 | 50 | 596 (228 ms) | 557 (279 ms); earlier run 588 | 07; 05 |
| itinerary, bus single-row | 100 | 240 (1473 ms) | 200 (694 ms); 409 % 0.3 / 75 / 72; 26 retries | 09 |
| itinerary, bus sharded-16 | 100 | 601 (524 ms) | 527 (410 ms); 409 % 0.1 / 59 / 59 | 09 |
| range any-room | 50 | 1137 (163 ms) | 1054 (146 ms); earlier run 1191 | 07; 05 |
| range any-room | 100 | 1083 (515 ms) | 1062 (718 ms); runs 921 / 1797 / 1062 | 07 |

**Pool 50 is no better in any scenario.**

- More concurrent transactions mean more lock waiters: 22–46 at pool 50, against 1–17 at pool 20.
- At 100 threads, holds outrun the cancels, so the 60 seats and rooms run out and 409 % jumps to 59–75 %.
- The application default stays at **pool 20**.

## 9. Conclusions

1. **Sharding the CAPACITY counter helps, a lot.**
   - At pool 20: about **4.4–5.0× more holds/s** at 50–200 threads (best of 16/64 against the single row), and the
     sell-out is about 4× faster.
   - Lock waiters go from about 16 to about 0–3.
   - The single row tops out at about **1.4 k holds/s** however many threads there are.
2. **The sharded ceiling is about 6–7 k holds/s on this machine,** for every bucket count, thread count and pool size
   tried.
   - It is **not** row contention: there are ~0 lock waiters with 64 buckets.
   - The likely limits are per-transaction cost (one durable commit per hold with `synchronous_commit=on`, plus the
     SAVEPOINT/RELEASE round trips of the fast path), and the JVM and PostgreSQL sharing 8 vCPUs. This was **not
     profiled**, so it is a hypothesis.
   - **Redis is not justified:** the bottleneck after sharding is not a hot key, and an in-memory counter would trade
     away the durability and the single-transaction atomicity that the design relies on.
3. **Bucket count:** 16 is enough at pool 20, but at pool 50 sharded-16 falls back to blocking (about 24 waiters) and
   loses about 25 %, while 64 does not. **Use at least as many buckets as concurrent DB transactions.** At ≥100
   threads, pool 20, sharded-64 ≈ sharded-16.
4. **Itinerary:**
   - Throughput is set by the hottest row it touches.
   - Lock ordering with counters last, plus a sharded BUS, gives **~600 itineraries/s**, against 134–175 in the
     baseline. Each itinerary is 3 holds + 3 releases in 2 transactions.
5. **TIME_RANGE:** the SKIP LOCKED first pass gave **~2×** throughput.
6. **No deadlocks were recorded in any run** (`pg_stat_database.deadlocks` delta 0).
   - **Lock timeouts do happen** when a pool of 50 queues on one hot row.
   - They are now translated and retried, after the defect in section 6.

## 10. Limitations of these measurements

- **Service-level only.** HTTP, JSON (de)serialisation, JWT validation and the idempotency table are not exercised.
  Expect lower numbers through the API. No HTTP load test was run.
- **Shared machine.** The load generator, the application and PostgreSQL run on the same 8 vCPUs. This is a
  cloud VM that was paused at least twice (section 7), and runs of 10 s × 3 show visible noise.
- **Out of scope:**
  - PostgreSQL is untuned (128 MB shared_buffers);
  - no `synchronous_commit=off` or group-commit experiments;
  - no multi-instance application test;
  - no network latency between the application and the DB. That latency would make every lock-holding transaction
    longer, which makes sharding *more* important.
- Hold-then-cancel workloads keep inventory near-empty, so 409 rates partly measure the cancel lag, not real
  sell-outs. Sell-out correctness is checked separately in (a2) and in the IT suite.
