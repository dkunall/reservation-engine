# Benchmark run 2026-10-20T16:53:35Z

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
| itinerary-3-items (bus single-row) | 50 | 1 | 286 | 286 | 0.0 | 80.21 | 169.78 | 223.03 | 391.6 | 16.5 | 19 | 29.9 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 2 | 222 | 222 | 0.0 | 99.44 | 287.08 | 441.40 | 904.9 | 16.4 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 3 | 243 | 243 | 0.0 | 85.20 | 289.10 | 425.72 | 816.1 | 16.6 | 19 | 30.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus single-row) median** | 50 | - | **243** | | | | | **425.72** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 50 | 1 | 570 | 570 | 0.0 | 22.89 | 144.74 | 246.33 | 557.1 | 1.4 | 5 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 2 | 596 | 596 | 0.0 | 22.49 | 138.28 | 216.10 | 475.4 | 1.3 | 6 | 29.9 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 3 | 618 | 618 | 0.0 | 20.24 | 127.94 | 227.99 | 474.8 | 1.3 | 7 | 29.9 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 50 | - | **596** | | | | | **227.99** | | | | | | | |
| itinerary-3-items (bus single-row) | 100 | 1 | 240 | 241 | 0.4 | 129.89 | 778.37 | 1377.28 | 2911.0 | 16.8 | 19 | 80.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 100 | 2 | 229 | 230 | 0.6 | 117.34 | 845.14 | 1602.99 | 3042.9 | 17.0 | 19 | 80.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 100 | 3 | 253 | 253 | 0.0 | 86.15 | 793.19 | 1473.43 | 3306.6 | 17.1 | 19 | 80.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus single-row) median** | 100 | - | **240** | | | | | **1473.43** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 100 | 1 | 601 | 763 | 21.2 | 19.27 | 302.09 | 523.52 | 991.2 | 1.4 | 5 | 79.9 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 2 | 835 | 1055 | 20.9 | 13.77 | 218.62 | 357.59 | 902.9 | 1.5 | 4 | 79.9 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 3 | 375 | 391 | 4.1 | 31.67 | 524.39 | 1013.18 | 2683.8 | 3.8 | 12 | 80.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 100 | - | **601** | | | | | **523.52** | | | | | | | |

## (c) TIME_RANGE contention: any room, overlapping dates (hold then cancel)

Inventory: 40 rooms. Each request: 2 nights starting day 30 + (0..2), so periods overlap heavily.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| range-any-room | 50 | 1 | 1120 | 1120 | 0.0 | 13.91 | 93.66 | 162.54 | 280.9 | 0.2 | 2 | 29.9 | 0 | 0 | 0 |
| range-any-room | 50 | 2 | 1143 | 1143 | 0.0 | 13.81 | 93.17 | 150.09 | 354.1 | 0.2 | 2 | 29.9 | 0 | 0 | 0 |
| range-any-room | 50 | 3 | 1137 | 1137 | 0.0 | 14.17 | 92.38 | 163.28 | 458.4 | 0.1 | 2 | 30.0 | 0 | 0 | 0 |
| **range-any-room median** | 50 | - | **1137** | | | | | **162.54** | | | | | | | |
| range-any-room | 100 | 1 | 2046 | 2118 | 3.4 | 6.57 | 111.57 | 216.02 | 751.1 | 3.2 | 10 | 79.7 | 0 | 0 | 0 |
| range-any-room | 100 | 2 | 1083 | 1102 | 1.7 | 13.68 | 258.81 | 514.51 | 1722.0 | 1.4 | 8 | 79.8 | 0 | 0 | 0 |
| range-any-room | 100 | 3 | 983 | 1005 | 2.2 | 13.69 | 281.86 | 535.33 | 1039.7 | 2.1 | 9 | 79.2 | 0 | 0 | 0 |
| **range-any-room median** | 100 | - | **1083** | | | | | **514.51** | | | | | | | |

