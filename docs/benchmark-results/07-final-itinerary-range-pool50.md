# Benchmark run 2026-10-20T16:57:21Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 50; lock_timeout 5s (application default)
- Warm-up 5 s, then 3 measured runs x 10 s per configuration

## (b) Itinerary holds (3 items: BUS capacity + FLIGHT any seat + HOTEL any room; hold then cancel)

Inventory: 60 seats, 60 rooms (same dates), unlimited bus capacity (single row, or 16 buckets). Latency = itinerary hold only.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| itinerary-3-items (bus single-row) | 50 | 1 | 222 | 222 | 0.0 | 79.84 | 343.95 | 538.06 | 936.6 | 40.5 | 49 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 2 | 262 | 262 | 0.0 | 67.47 | 303.40 | 432.94 | 720.7 | 40.6 | 49 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 3 | 250 | 251 | 0.3 | 69.24 | 293.26 | 462.34 | 848.1 | 40.1 | 49 | 0.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus single-row) median** | 50 | - | **250** | | | | | **462.34** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 50 | 1 | 552 | 555 | 0.5 | 38.26 | 142.38 | 279.09 | 1298.3 | 13.6 | 29 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 2 | 557 | 557 | 0.1 | 38.42 | 139.59 | 282.77 | 885.7 | 13.3 | 27 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 3 | 567 | 567 | 0.0 | 48.85 | 111.71 | 170.59 | 603.8 | 7.8 | 18 | 0.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 50 | - | **557** | | | | | **279.09** | | | | | | | |
| itinerary-3-items (bus single-row) | 100 | 1 | 223 | 813 | 72.6 | 40.44 | 260.75 | 522.53 | 5810.2 | 43.0 | 49 | 49.5 | 0 | 13 | 0 |
| itinerary-3-items (bus single-row) | 100 | 2 | 4 | 4 | 0.4 | 98.68 | 393.70 | 1690.45 | 2592.3 | 45.6 | 49 | 48.7 | 0 | 40 | 24 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| itinerary-3-items (bus single-row) | 100 | 3 | 60 | 61 | 0.2 | 120.22 | 390.40 | 2429.09 | 4159.9 | 45.7 | 49 | 49.0 | 0 | 35 | 31 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| **itinerary-3-items (bus single-row) median** | 100 | - | **60** | | | | | **1690.45** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 100 | 1 | 388 | 930 | 58.3 | 26.58 | 290.27 | 503.42 | 1407.0 | 13.0 | 34 | 49.6 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 2 | 315 | 867 | 63.7 | 20.65 | 295.61 | 502.39 | 1484.9 | 16.9 | 31 | 49.5 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 3 | 481 | 1099 | 56.2 | 24.99 | 257.13 | 501.14 | 1441.9 | 12.2 | 28 | 49.7 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 100 | - | **388** | | | | | **502.39** | | | | | | | |

## (c) TIME_RANGE contention: any room, overlapping dates (hold then cancel)

Inventory: 40 rooms. Each request: 2 nights starting day 30 + (0..2), so periods overlap heavily.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| range-any-room | 50 | 1 | 1054 | 1054 | 0.0 | 24.08 | 94.36 | 139.75 | 385.1 | 1.0 | 7 | 0.0 | 0 | 0 | 0 |
| range-any-room | 50 | 2 | 883 | 883 | 0.0 | 32.65 | 106.58 | 164.20 | 665.2 | 1.8 | 19 | 0.0 | 0 | 0 | 0 |
| range-any-room | 50 | 3 | 1215 | 1215 | 0.0 | 18.99 | 84.32 | 145.69 | 444.2 | 3.0 | 15 | 0.0 | 0 | 0 | 0 |
| **range-any-room median** | 50 | - | **1054** | | | | | **145.69** | | | | | | | |
| range-any-room | 100 | 1 | 921 | 925 | 0.4 | 20.30 | 218.42 | 1019.56 | 1602.8 | 24.5 | 34 | 49.4 | 0 | 0 | 0 |
| range-any-room | 100 | 2 | 1797 | 1800 | 0.2 | 11.44 | 127.88 | 435.16 | 1044.5 | 22.8 | 34 | 49.7 | 0 | 0 | 0 |
| range-any-room | 100 | 3 | 1062 | 1064 | 0.1 | 20.27 | 245.75 | 717.68 | 1323.0 | 22.1 | 33 | 49.7 | 0 | 0 | 0 |
| **range-any-room median** | 100 | - | **1062** | | | | | **717.68** | | | | | | | |

