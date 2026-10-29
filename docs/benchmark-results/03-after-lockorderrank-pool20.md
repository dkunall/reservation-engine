# Benchmark run 2026-10-20T16:34:10Z

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
| itinerary-3-items | 50 | 1 | 235 | 235 | 0.0 | 95.29 | 211.15 | 291.99 | 586.5 | 16.9 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items | 50 | 2 | 218 | 218 | 0.0 | 102.47 | 258.32 | 375.18 | 619.0 | 16.9 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items | 50 | 3 | 194 | 194 | 0.0 | 100.13 | 384.87 | 620.48 | 969.3 | 16.5 | 19 | 30.0 | 0 | 0 | 0 |
| **itinerary-3-items median** | 50 | - | **218** | | | | | **375.18** | | | | | | | |
| itinerary-3-items | 100 | 1 | 224 | 238 | 5.9 | 129.80 | 773.50 | 1365.88 | 3532.4 | 17.1 | 19 | 79.8 | 0 | 0 | 0 |
| itinerary-3-items | 100 | 2 | 252 | 252 | 0.2 | 133.50 | 730.06 | 1286.92 | 2860.5 | 16.7 | 19 | 80.0 | 0 | 0 | 0 |
| itinerary-3-items | 100 | 3 | 245 | 248 | 1.3 | 149.51 | 698.80 | 1181.50 | 2428.6 | 16.8 | 19 | 80.0 | 0 | 0 | 0 |
| **itinerary-3-items median** | 100 | - | **245** | | | | | **1286.92** | | | | | | | |

