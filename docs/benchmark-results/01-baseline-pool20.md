# Benchmark run 2026-10-20T16:20:46Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; lock_timeout 5s (application default)
- Warm-up 5 s, then 3 measured runs x 10 s per configuration

## (a) CAPACITY hold throughput: single row vs sharded buckets (no sell-out, qty 1)

| layout | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| single-row | 50 | 1 | 1372 | 1372 | 0.0 | 31.53 | 78.20 | 107.44 | 209.9 | 15.9 | 19 | 30.0 | 0 | 0 | 0 |
| single-row | 50 | 2 | 1408 | 1408 | 0.0 | 28.58 | 89.83 | 130.85 | 445.6 | 16.9 | 19 | 30.0 | 0 | 0 | 0 |
| single-row | 50 | 3 | 1290 | 1290 | 0.0 | 28.18 | 112.18 | 172.42 | 391.3 | 17.0 | 19 | 30.0 | 0 | 0 | 0 |
| **single-row median** | 50 | - | **1372** | | | | | **130.85** | | | | | | | |
| sharded-16 | 50 | 1 | 2934 | 2934 | 0.0 | 6.86 | 69.88 | 127.33 | 402.2 | 2.9 | 6 | 29.9 | 0 | 0 | 0 |
| sharded-16 | 50 | 2 | 4167 | 4167 | 0.0 | 5.21 | 45.81 | 77.35 | 190.1 | 2.1 | 4 | 29.8 | 0 | 0 | 0 |
| sharded-16 | 50 | 3 | 6536 | 6536 | 0.0 | 3.36 | 27.15 | 42.64 | 127.8 | 1.0 | 4 | 29.6 | 0 | 0 | 0 |
| **sharded-16 median** | 50 | - | **4167** | | | | | **77.35** | | | | | | | |
| sharded-64 | 50 | 1 | 6655 | 6655 | 0.0 | 3.28 | 27.22 | 43.64 | 119.4 | 0.0 | 0 | 29.7 | 0 | 0 | 0 |
| sharded-64 | 50 | 2 | 6925 | 6925 | 0.0 | 3.25 | 25.65 | 40.17 | 108.8 | 0.0 | 0 | 29.7 | 0 | 0 | 0 |
| sharded-64 | 50 | 3 | 6968 | 6968 | 0.0 | 3.30 | 25.61 | 39.14 | 98.0 | 0.0 | 0 | 29.6 | 0 | 0 | 0 |
| **sharded-64 median** | 50 | - | **6925** | | | | | **40.17** | | | | | | | |
| single-row | 100 | 1 | 1436 | 1436 | 0.0 | 35.48 | 237.35 | 384.80 | 982.9 | 16.5 | 19 | 80.0 | 0 | 0 | 0 |
| single-row | 100 | 2 | 1498 | 1498 | 0.0 | 26.25 | 236.98 | 392.15 | 836.4 | 16.2 | 19 | 80.0 | 0 | 0 | 0 |
| single-row | 100 | 3 | 1543 | 1543 | 0.0 | 35.08 | 221.72 | 355.33 | 1011.5 | 16.5 | 19 | 80.0 | 0 | 0 | 0 |
| **single-row median** | 100 | - | **1498** | | | | | **384.80** | | | | | | | |
| sharded-16 | 100 | 1 | 6093 | 6093 | 0.0 | 3.61 | 70.03 | 110.74 | 298.7 | 1.2 | 4 | 79.2 | 0 | 0 | 0 |
| sharded-16 | 100 | 2 | 6127 | 6127 | 0.0 | 3.57 | 70.03 | 107.92 | 314.2 | 1.3 | 4 | 79.6 | 0 | 0 | 0 |
| sharded-16 | 100 | 3 | 6468 | 6468 | 0.0 | 3.42 | 66.36 | 105.08 | 279.5 | 0.8 | 4 | 79.7 | 0 | 0 | 0 |
| **sharded-16 median** | 100 | - | **6127** | | | | | **107.92** | | | | | | | |
| sharded-64 | 100 | 1 | 6621 | 6621 | 0.0 | 3.37 | 65.69 | 103.49 | 266.2 | 0.0 | 0 | 79.7 | 0 | 0 | 0 |
| sharded-64 | 100 | 2 | 4889 | 4889 | 0.0 | 4.46 | 95.36 | 160.06 | 365.9 | 0.0 | 0 | 79.6 | 0 | 0 | 0 |
| sharded-64 | 100 | 3 | 7034 | 7034 | 0.0 | 3.25 | 61.20 | 94.92 | 213.6 | 0.0 | 0 | 79.7 | 0 | 0 | 0 |
| **sharded-64 median** | 100 | - | **6621** | | | | | **103.49** | | | | | | | |
| single-row | 200 | 1 | 1367 | 1367 | 0.0 | 16.07 | 748.64 | 1560.51 | 3895.7 | 16.4 | 19 | 180.0 | 0 | 0 | 0 |
| single-row | 200 | 2 | 1379 | 1379 | 0.0 | 16.74 | 727.22 | 1375.17 | 3829.4 | 16.5 | 19 | 180.0 | 0 | 0 | 0 |
| single-row | 200 | 3 | 1447 | 1447 | 0.0 | 17.13 | 654.34 | 1218.86 | 4252.3 | 16.4 | 19 | 180.0 | 0 | 0 | 0 |
| **single-row median** | 200 | - | **1379** | | | | | **1375.17** | | | | | | | |
| sharded-16 | 200 | 1 | 2963 | 2963 | 0.0 | 7.33 | 338.35 | 639.33 | 1838.9 | 2.2 | 4 | 178.2 | 0 | 0 | 0 |
| sharded-16 | 200 | 2 | 6163 | 6163 | 0.0 | 3.62 | 151.11 | 241.29 | 561.9 | 0.8 | 4 | 179.8 | 0 | 0 | 0 |
| sharded-16 | 200 | 3 | 6487 | 6487 | 0.0 | 3.50 | 141.50 | 222.32 | 524.5 | 0.5 | 4 | 179.7 | 0 | 0 | 0 |
| **sharded-16 median** | 200 | - | **6163** | | | | | **241.29** | | | | | | | |
| sharded-64 | 200 | 1 | 3113 | 3113 | 0.0 | 7.41 | 347.56 | 668.84 | 1890.3 | 0.0 | 0 | 178.2 | 0 | 0 | 0 |
| sharded-64 | 200 | 2 | 6638 | 6638 | 0.0 | 3.47 | 138.86 | 218.38 | 573.6 | 0.0 | 0 | 179.6 | 0 | 0 | 0 |
| sharded-64 | 200 | 3 | 5960 | 5960 | 0.0 | 3.79 | 156.73 | 246.39 | 554.8 | 0.0 | 1 | 179.1 | 0 | 0 | 0 |
| **sharded-64 median** | 200 | - | **5960** | | | | | **246.39** | | | | | | | |

## (a2) Flash-sale sell-out: 20000 units, 200 threads, qty 1, until sold out

| layout | run | sold | oversold | 409s while selling | sell-out time s | sold/s | p50 ms | p95 ms | p99 ms | deadlocks |
|---|---|---|---|---|---|---|---|---|---|---|
| single-row | 1 | 20000 | 0 | 200 | 13.52 | 1479 | 120.81 | 399.88 | 627.79 | 0 |
| single-row | 2 | 20000 | 0 | 199 | 13.96 | 1433 | 117.38 | 493.00 | 798.63 | 0 |
| single-row | 3 | 20000 | 0 | 199 | 14.29 | 1399 | 15.80 | 743.48 | 1486.61 | 0 |
| sharded-16 | 1 | 20000 | 0 | 200 | 3.36 | 5951 | 3.66 | 151.58 | 243.74 | 0 |
| sharded-16 | 2 | 20000 | 0 | 200 | 3.62 | 5528 | 3.93 | 167.62 | 266.72 | 0 |
| sharded-16 | 3 | 20000 | 0 | 200 | 3.34 | 5981 | 3.65 | 153.42 | 241.92 | 0 |
| sharded-64 | 1 | 20000 | 0 | 200 | 3.34 | 5980 | 3.67 | 158.24 | 259.07 | 0 |
| sharded-64 | 2 | 20000 | 0 | 200 | 3.13 | 6391 | 3.54 | 142.53 | 229.17 | 0 |
| sharded-64 | 3 | 20000 | 0 | 200 | 3.18 | 6284 | 3.50 | 147.57 | 230.24 | 0 |

(409s while selling = requests rejected because stock was exhausted at the moment they ran; each thread stops at its first 409.)

## (b) Itinerary holds (3 items: BUS capacity + FLIGHT any seat + HOTEL any room; hold then cancel)

Inventory: 60 seats, 60 rooms (same dates), unlimited bus capacity. Latency = itinerary hold only.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| itinerary-3-items | 50 | 1 | 175 | 175 | 0.0 | 162.52 | 584.75 | 880.35 | 1639.9 | 17.4 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items | 50 | 2 | 183 | 183 | 0.0 | 145.55 | 492.92 | 709.47 | 1186.0 | 17.0 | 19 | 30.0 | 0 | 0 | 0 |
| itinerary-3-items | 50 | 3 | 122 | 122 | 0.0 | 113.12 | 657.53 | 1244.75 | 2470.6 | 17.9 | 19 | 30.0 | 0 | 0 | 0 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| **itinerary-3-items median** | 50 | - | **175** | | | | | **880.35** | | | | | | | |
| itinerary-3-items | 100 | 1 | 98 | 98 | 0.0 | 483.18 | 1407.72 | 2246.00 | 2766.3 | 18.7 | 19 | 80.0 | 0 | 0 | 1 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| itinerary-3-items | 100 | 2 | 141 | 141 | 0.0 | 224.79 | 1371.90 | 2232.52 | 4589.3 | 18.1 | 19 | 80.0 | 0 | 0 | 0 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| itinerary-3-items | 100 | 3 | 134 | 139 | 0.0 | 91.72 | 1654.84 | 3934.27 | 4915.2 | 18.3 | 19 | 79.2 | 0 | 0 | 47 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| **itinerary-3-items median** | 100 | - | **134** | | | | | **2246.00** | | | | | | | |

## (c) TIME_RANGE contention: any room, overlapping dates (hold then cancel)

Inventory: 40 rooms. Each request: 2 nights starting day 30 + (0..2), so periods overlap heavily.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| range-any-room | 50 | 1 | 522 | 522 | 0.0 | 45.22 | 192.61 | 300.87 | 628.3 | 12.9 | 19 | 30.0 | 0 | 0 | 0 |
| range-any-room | 50 | 2 | 545 | 545 | 0.0 | 45.40 | 176.30 | 277.34 | 481.6 | 12.8 | 17 | 29.9 | 0 | 0 | 0 |
| range-any-room | 50 | 3 | 547 | 547 | 0.0 | 45.80 | 176.77 | 277.27 | 508.7 | 12.4 | 19 | 29.9 | 0 | 0 | 0 |
| **range-any-room median** | 50 | - | **545** | | | | | **277.34** | | | | | | | |
| range-any-room | 100 | 1 | 555 | 566 | 1.9 | 47.47 | 386.94 | 704.60 | 2401.1 | 11.7 | 18 | 80.0 | 0 | 0 | 0 |
| range-any-room | 100 | 2 | 625 | 652 | 4.2 | 39.98 | 336.13 | 632.83 | 1322.7 | 11.6 | 18 | 79.9 | 0 | 0 | 0 |
| range-any-room | 100 | 3 | 543 | 561 | 3.3 | 44.98 | 387.57 | 746.19 | 2029.4 | 11.2 | 18 | 80.0 | 0 | 0 | 0 |
| **range-any-room median** | 100 | - | **555** | | | | | **704.60** | | | | | | | |

