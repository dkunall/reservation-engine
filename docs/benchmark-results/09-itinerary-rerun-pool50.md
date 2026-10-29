# Benchmark run 2026-10-20T17:07:46Z

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
| itinerary-3-items (bus single-row) | 100 | 1 | 177 | 178 | 0.3 | 187.31 | 506.21 | 5299.01 | 5769.0 | 46.4 | 49 | 49.5 | 0 | 12 | 0 |
| itinerary-3-items (bus single-row) | 100 | 2 | 200 | 804 | 75.1 | 39.90 | 287.56 | 590.98 | 5575.7 | 41.3 | 49 | 49.7 | 0 | 4 | 0 |
| itinerary-3-items (bus single-row) | 100 | 3 | 208 | 735 | 71.7 | 34.22 | 334.55 | 694.48 | 7075.5 | 42.0 | 49 | 49.7 | 0 | 10 | 0 |
| **itinerary-3-items (bus single-row) median** | 100 | - | **200** | | | | | **694.48** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 100 | 1 | 532 | 532 | 0.1 | 32.92 | 397.11 | 1373.97 | 3018.4 | 25.5 | 34 | 49.5 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 2 | 505 | 1240 | 59.2 | 20.44 | 225.55 | 409.77 | 1760.9 | 11.9 | 25 | 50.0 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 3 | 527 | 1276 | 58.7 | 19.85 | 223.63 | 391.79 | 910.5 | 9.2 | 24 | 49.9 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 100 | - | **527** | | | | | **409.77** | | | | | | | |

