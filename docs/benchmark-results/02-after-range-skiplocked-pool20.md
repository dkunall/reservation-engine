# Benchmark run 2026-10-20T16:30:52Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; lock_timeout 5s (application default)
- Warm-up 5 s, then 3 measured runs x 10 s per configuration

## (b) Itinerary holds (3 items: BUS capacity + FLIGHT any seat + HOTEL any room; hold then cancel)

Inventory: 60 seats, 60 rooms (same dates), unlimited bus capacity. Latency = itinerary hold only.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| itinerary-3-items | 50 | 1 | 125 | 125 | 0.0 | 178.45 | 369.46 | 505.41 | 699.9 | 17.4 | 19 | 29.8 | 0 | 0 | 0 |
| itinerary-3-items | 50 | 2 | 101 | 101 | 0.0 | 216.57 | 452.50 | 663.41 | 957.4 | 18.3 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items | 50 | 3 | 97 | 97 | 0.0 | 245.49 | 650.45 | 975.21 | 1458.9 | 18.4 | 19 | 30.0 | 0 | 0 | 0 |
| **itinerary-3-items median** | 50 | - | **101** | | | | | **663.41** | | | | | | | |
| itinerary-3-items | 100 | 1 | 95 | 95 | 0.0 | 391.44 | 1737.59 | 3097.59 | 4581.3 | 18.6 | 19 | 80.0 | 0 | 0 | 1 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| itinerary-3-items | 100 | 2 | 341 | 347 | 1.9 | 123.95 | 470.55 | 721.42 | 1262.9 | 16.3 | 19 | 80.0 | 0 | 0 | 0 |
| itinerary-3-items | 100 | 3 | 259 | 260 | 0.4 | 101.51 | 766.80 | 1483.38 | 3541.4 | 16.8 | 19 | 79.8 | 0 | 0 | 0 |
| **itinerary-3-items median** | 100 | - | **259** | | | | | **1483.38** | | | | | | | |

## (c) TIME_RANGE contention: any room, overlapping dates (hold then cancel)

Inventory: 40 rooms. Each request: 2 nights starting day 30 + (0..2), so periods overlap heavily.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| range-any-room | 50 | 1 | 1080 | 1080 | 0.0 | 14.09 | 93.00 | 159.10 | 338.2 | 0.1 | 1 | 29.6 | 0 | 0 | 0 |
| range-any-room | 50 | 2 | 1127 | 1127 | 0.0 | 13.97 | 90.94 | 156.68 | 309.7 | 0.2 | 2 | 29.9 | 0 | 0 | 0 |
| range-any-room | 50 | 3 | 1128 | 1128 | 0.0 | 13.61 | 93.76 | 158.47 | 388.6 | 0.2 | 2 | 30.0 | 0 | 0 | 0 |
| **range-any-room median** | 50 | - | **1127** | | | | | **158.47** | | | | | | | |
| range-any-room | 100 | 1 | 2536 | 2649 | 4.2 | 5.30 | 81.07 | 124.30 | 276.6 | 3.1 | 11 | 79.8 | 0 | 0 | 0 |
| range-any-room | 100 | 2 | 1079 | 1133 | 4.8 | 14.03 | 217.16 | 386.07 | 862.9 | 2.4 | 8 | 79.9 | 0 | 0 | 0 |
| range-any-room | 100 | 3 | 1109 | 1166 | 4.9 | 13.93 | 198.97 | 360.47 | 915.1 | 2.8 | 11 | 79.5 | 0 | 0 | 0 |
| **range-any-room median** | 100 | - | **1109** | | | | | **360.47** | | | | | | | |

