# Benchmark run 2026-10-20T16:35:52Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; lock_timeout 5s (application default)
- Warm-up 5 s, then 3 measured runs x 10 s per configuration

## (b) Itinerary holds (3 items: BUS capacity + FLIGHT any seat + HOTEL any room; hold then cancel)

Inventory: 60 seats, 60 rooms (same dates), unlimited bus capacity (single row, or 16 buckets). Latency = itinerary hold only.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| itinerary-3-items (bus single-row) | 50 | 1 | 222 | 222 | 0.0 | 102.62 | 226.26 | 316.03 | 534.1 | 16.2 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 2 | 223 | 223 | 0.0 | 101.35 | 243.98 | 330.18 | 580.7 | 17.1 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 3 | 239 | 239 | 0.0 | 87.73 | 290.91 | 446.87 | 614.7 | 16.7 | 19 | 30.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus single-row) median** | 50 | - | **223** | | | | | **330.18** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 50 | 1 | 486 | 486 | 0.0 | 23.40 | 170.87 | 313.33 | 697.8 | 1.7 | 7 | 29.9 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 2 | 945 | 945 | 0.0 | 12.96 | 82.10 | 134.99 | 394.9 | 1.5 | 5 | 29.9 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 3 | 589 | 589 | 0.0 | 22.73 | 148.60 | 233.98 | 508.8 | 1.3 | 4 | 29.9 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 50 | - | **589** | | | | | **233.98** | | | | | | | |
| itinerary-3-items (bus single-row) | 100 | 1 | 225 | 232 | 3.2 | 96.70 | 835.01 | 1491.21 | 3097.4 | 16.5 | 19 | 80.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 100 | 2 | 226 | 242 | 6.5 | 124.33 | 777.35 | 1325.03 | 2747.1 | 16.9 | 19 | 80.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 100 | 3 | 237 | 251 | 5.7 | 96.96 | 777.97 | 1353.72 | 2375.4 | 17.0 | 19 | 80.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus single-row) median** | 100 | - | **226** | | | | | **1353.72** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 100 | 1 | 1078 | 1365 | 21.0 | 11.20 | 164.08 | 241.07 | 559.6 | 1.1 | 5 | 79.7 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 2 | 503 | 534 | 5.8 | 25.54 | 370.24 | 645.40 | 1498.0 | 6.4 | 14 | 79.6 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 3 | 545 | 574 | 5.1 | 23.31 | 351.23 | 562.28 | 1048.5 | 5.5 | 13 | 79.5 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 100 | - | **545** | | | | | **562.28** | | | | | | | |

