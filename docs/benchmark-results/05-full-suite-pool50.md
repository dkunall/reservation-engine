# Benchmark run 2026-10-20T16:38:26Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 50; lock_timeout 5s (application default)
- Warm-up 5 s, then 3 measured runs x 10 s per configuration

## (a) CAPACITY hold throughput: single row vs sharded buckets (no sell-out, qty 1)

| layout | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| single-row | 50 | 1 | 1156 | 1156 | 0.0 | 29.32 | 133.11 | 209.90 | 465.8 | 36.3 | 49 | 0.0 | 0 | 0 | 0 |
| single-row | 50 | 2 | 1187 | 1187 | 0.0 | 29.11 | 125.98 | 191.94 | 575.8 | 35.6 | 49 | 0.0 | 0 | 0 | 0 |
| single-row | 50 | 3 | 1199 | 1199 | 0.0 | 29.42 | 121.93 | 193.58 | 518.1 | 38.3 | 49 | 0.0 | 0 | 0 | 0 |
| **single-row median** | 50 | - | **1187** | | | | | **193.58** | | | | | | | |
| sharded-16 | 50 | 1 | 4624 | 4624 | 0.0 | 4.37 | 28.57 | 189.16 | 780.5 | 23.6 | 34 | 0.0 | 0 | 0 | 0 |
| sharded-16 | 50 | 2 | 4236 | 4236 | 0.0 | 4.53 | 35.35 | 204.15 | 926.1 | 23.5 | 34 | 0.0 | 0 | 0 | 0 |
| sharded-16 | 50 | 3 | 4515 | 4515 | 0.0 | 4.44 | 30.81 | 191.76 | 794.4 | 21.9 | 34 | 0.0 | 0 | 0 | 0 |
| **sharded-16 median** | 50 | - | **4515** | | | | | **191.76** | | | | | | | |
| sharded-64 | 50 | 1 | 6001 | 6001 | 0.0 | 7.81 | 13.16 | 20.03 | 64.5 | 0.0 | 0 | 0.0 | 0 | 0 | 0 |
| sharded-64 | 50 | 2 | 6112 | 6112 | 0.0 | 7.77 | 12.83 | 17.49 | 59.2 | 0.5 | 47 | 0.0 | 0 | 0 | 0 |
| sharded-64 | 50 | 3 | 5938 | 5938 | 0.0 | 7.92 | 12.86 | 19.42 | 59.3 | 0.3 | 22 | 0.0 | 0 | 0 | 0 |
| **sharded-64 median** | 50 | - | **6001** | | | | | **19.42** | | | | | | | |
| single-row | 100 | 1 | 1176 | 1176 | 0.0 | 58.33 | 252.52 | 387.39 | 1162.5 | 38.2 | 49 | 50.0 | 0 | 0 | 0 |
| single-row | 100 | 2 | 1183 | 1183 | 0.0 | 60.70 | 241.67 | 363.09 | 883.3 | 36.5 | 49 | 50.0 | 0 | 0 | 0 |
| single-row | 100 | 3 | 1186 | 1186 | 0.0 | 58.47 | 244.47 | 377.71 | 1380.8 | 35.4 | 49 | 50.0 | 0 | 0 | 0 |
| **single-row median** | 100 | - | **1183** | | | | | **377.71** | | | | | | | |
| sharded-16 | 100 | 1 | 4592 | 4592 | 0.0 | 5.61 | 85.71 | 192.92 | 735.7 | 23.3 | 37 | 49.8 | 0 | 0 | 0 |
| sharded-16 | 100 | 2 | 4491 | 4491 | 0.0 | 5.57 | 88.53 | 198.14 | 783.3 | 25.3 | 34 | 49.4 | 0 | 0 | 0 |
| sharded-16 | 100 | 3 | 4572 | 4572 | 0.0 | 5.53 | 86.99 | 195.67 | 651.4 | 23.9 | 34 | 49.5 | 0 | 0 | 0 |
| **sharded-16 median** | 100 | - | **4572** | | | | | **195.67** | | | | | | | |
| sharded-64 | 100 | 1 | 6244 | 6244 | 0.0 | 8.07 | 70.78 | 144.89 | 879.2 | 0.1 | 12 | 49.9 | 0 | 0 | 0 |
| sharded-64 | 100 | 2 | 6305 | 6305 | 0.0 | 7.98 | 70.17 | 138.52 | 431.8 | 0.0 | 0 | 49.9 | 0 | 0 | 0 |
| sharded-64 | 100 | 3 | 6279 | 6279 | 0.0 | 7.89 | 69.49 | 135.71 | 520.5 | 0.0 | 2 | 49.6 | 0 | 0 | 0 |
| **sharded-64 median** | 100 | - | **6279** | | | | | **138.52** | | | | | | | |
| single-row | 200 | 1 | 1115 | 1115 | 0.0 | 148.26 | 471.25 | 721.59 | 1867.7 | 35.0 | 49 | 148.6 | 0 | 0 | 0 |
| single-row | 200 | 2 | 1164 | 1164 | 0.0 | 62.65 | 656.78 | 1174.34 | 3030.6 | 37.7 | 49 | 150.0 | 0 | 0 | 0 |
| single-row | 200 | 3 | 1142 | 1142 | 0.0 | 52.80 | 723.77 | 1383.66 | 3837.1 | 37.4 | 49 | 149.8 | 0 | 0 | 1 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| **single-row median** | 200 | - | **1142** | | | | | **1174.34** | | | | | | | |
| sharded-16 | 200 | 1 | 4123 | 4123 | 0.0 | 5.98 | 227.55 | 377.65 | 955.5 | 21.6 | 32 | 148.5 | 0 | 0 | 0 |
| sharded-16 | 200 | 2 | 4271 | 4271 | 0.0 | 5.72 | 220.88 | 377.88 | 1008.4 | 25.1 | 34 | 148.3 | 0 | 0 | 0 |
| sharded-16 | 200 | 3 | 4377 | 4377 | 0.0 | 5.55 | 215.73 | 368.58 | 861.3 | 23.6 | 34 | 148.3 | 0 | 0 | 0 |
| **sharded-16 median** | 200 | - | **4271** | | | | | **377.65** | | | | | | | |
| sharded-64 | 200 | 1 | 6175 | 6175 | 0.0 | 8.17 | 195.56 | 419.29 | 1278.6 | 0.0 | 1 | 149.3 | 0 | 0 | 0 |
| sharded-64 | 200 | 2 | 6418 | 6418 | 0.0 | 7.80 | 184.73 | 372.62 | 1079.7 | 0.0 | 1 | 149.3 | 0 | 0 | 0 |
| sharded-64 | 200 | 3 | 6307 | 6307 | 0.0 | 7.95 | 190.11 | 383.54 | 1170.3 | 0.1 | 4 | 148.9 | 0 | 0 | 0 |
| **sharded-64 median** | 200 | - | **6307** | | | | | **383.54** | | | | | | | |

## (a2) Flash-sale sell-out: 20000 units, 200 threads, qty 1, until sold out

| layout | run | sold | oversold | 409s while selling | sell-out time s | sold/s | p50 ms | p95 ms | p99 ms | deadlocks |
|---|---|---|---|---|---|---|---|---|---|---|
| single-row | 1 | 20000 | 0 | 199 | 17.46 | 1146 | 101.25 | 582.40 | 996.35 | 0 |
| single-row | 2 | 20000 | 0 | 200 | 16.42 | 1218 | 101.93 | 551.06 | 898.85 | 0 |
| single-row | 3 | 20000 | 0 | 197 | 16.48 | 1214 | 42.12 | 768.75 | 1635.14 | 0 |
| sharded-16 | 1 | 20000 | 0 | 200 | 4.68 | 4270 | 5.41 | 219.76 | 369.52 | 0 |
| sharded-16 | 2 | 20000 | 0 | 200 | 4.90 | 4078 | 5.54 | 227.47 | 382.07 | 0 |
| sharded-16 | 3 | 20000 | 0 | 200 | 4.78 | 4186 | 5.39 | 226.75 | 382.96 | 0 |
| sharded-64 | 1 | 20000 | 0 | 200 | 3.42 | 5849 | 8.04 | 192.07 | 451.57 | 0 |
| sharded-64 | 2 | 20000 | 0 | 200 | 3.55 | 5642 | 8.43 | 205.12 | 452.56 | 0 |
| sharded-64 | 3 | 20000 | 0 | 200 | 3.39 | 5894 | 8.08 | 194.81 | 444.45 | 0 |

(409s while selling = requests rejected because stock was exhausted at the moment they ran; each thread stops at its first 409.)

## (b) Itinerary holds (3 items: BUS capacity + FLIGHT any seat + HOTEL any room; hold then cancel)

Inventory: 60 seats, 60 rooms (same dates), unlimited bus capacity (single row, or 16 buckets). Latency = itinerary hold only.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| itinerary-3-items (bus single-row) | 50 | 1 | 236 | 236 | 0.0 | 69.62 | 327.53 | 503.78 | 1153.1 | 40.8 | 49 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 2 | 272 | 272 | 0.0 | 54.45 | 258.77 | 536.43 | 3367.6 | 42.2 | 49 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 50 | 3 | 257 | 257 | 0.0 | 67.29 | 290.55 | 444.73 | 855.1 | 39.9 | 49 | 0.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus single-row) median** | 50 | - | **257** | | | | | **503.78** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 50 | 1 | 590 | 590 | 0.1 | 40.09 | 113.68 | 217.98 | 748.5 | 11.0 | 26 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 2 | 544 | 544 | 0.0 | 47.61 | 123.07 | 203.42 | 599.6 | 9.3 | 23 | 0.0 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 50 | 3 | 588 | 588 | 0.0 | 48.44 | 107.25 | 146.03 | 303.0 | 6.4 | 16 | 0.0 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 50 | - | **588** | | | | | **203.42** | | | | | | | |
| itinerary-3-items (bus single-row) | 100 | 1 | 338 | 339 | 0.5 | 92.69 | 395.67 | 3117.62 | 4115.5 | 45.1 | 49 | 49.5 | 0 | 0 | 0 |
| itinerary-3-items (bus single-row) | 100 | 2 | 237 | 241 | 1.4 | 123.51 | 592.95 | 3933.33 | 5644.9 | 45.4 | 49 | 49.8 | 0 | 0 | 4 |
<!-- first error: UncategorizedSQLException: PreparedStatementCallback; uncategorized SQLException for SQL [SELECT NOT EXISTS (SELECT 1 FROM range_allocation a
                    WHERE a.unit_id = u.id AND a.status = 'ACTIVE'
                      AND a.period && tstzrange(?, ?, '[)'))
  FROM inventory_unit u WHERE u.id = ? FOR UPDATE OF u
]; SQL state [55P03]; error code [0]; ERROR: canceling statement due to lock timeout -->
| itinerary-3-items (bus single-row) | 100 | 3 | 214 | 748 | 71.4 | 40.81 | 308.15 | 524.91 | 5369.8 | 43.3 | 49 | 49.8 | 0 | 0 | 3 |
<!-- first error: UncategorizedSQLException: PreparedStatementCallback; uncategorized SQLException for SQL [SELECT NOT EXISTS (SELECT 1 FROM range_allocation a
                    WHERE a.unit_id = u.id AND a.status = 'ACTIVE'
                      AND a.period && tstzrange(?, ?, '[)'))
  FROM inventory_unit u WHERE u.id = ? FOR UPDATE OF u
]; SQL state [55P03]; error code [0]; ERROR: canceling statement due to lock timeout -->
| **itinerary-3-items (bus single-row) median** | 100 | - | **237** | | | | | **3117.62** | | | | | | | |
| itinerary-3-items (bus sharded-16) | 100 | 1 | 580 | 1322 | 56.1 | 23.11 | 211.49 | 368.48 | 1016.3 | 8.0 | 22 | 49.8 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 2 | 566 | 567 | 0.1 | 30.52 | 337.07 | 1416.98 | 2227.7 | 25.9 | 40 | 49.5 | 0 | 0 | 0 |
| itinerary-3-items (bus sharded-16) | 100 | 3 | 753 | 753 | 0.0 | 24.31 | 260.06 | 1188.89 | 2106.1 | 25.2 | 34 | 49.5 | 0 | 0 | 0 |
| **itinerary-3-items (bus sharded-16) median** | 100 | - | **580** | | | | | **1188.89** | | | | | | | |

## (c) TIME_RANGE contention: any room, overlapping dates (hold then cancel)

Inventory: 40 rooms. Each request: 2 nights starting day 30 + (0..2), so periods overlap heavily.

| scenario | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| range-any-room | 50 | 1 | 1127 | 1127 | 0.0 | 24.30 | 87.28 | 114.45 | 279.6 | 1.1 | 9 | 0.0 | 0 | 0 | 0 |
| range-any-room | 50 | 2 | 1309 | 1309 | 0.0 | 18.27 | 80.57 | 121.40 | 512.3 | 2.0 | 11 | 0.0 | 0 | 0 | 0 |
| range-any-room | 50 | 3 | 1191 | 1191 | 0.0 | 22.14 | 86.40 | 118.71 | 268.5 | 0.8 | 9 | 0.0 | 0 | 0 | 0 |
| **range-any-room median** | 50 | - | **1191** | | | | | **118.71** | | | | | | | |
| range-any-room | 100 | 1 | 958 | 959 | 0.1 | 26.38 | 314.55 | 720.03 | 2219.4 | 18.1 | 30 | 49.9 | 0 | 0 | 0 |
| range-any-room | 100 | 2 | 77 | 78 | 0.2 | 11.08 | 132.14 | 431.20 | 748.3 | 22.9 | 32 | 49.2 | 0 | 0 | 51 |
<!-- first error: CannotCreateTransactionException: Could not open JPA EntityManager for transaction -->
| range-any-room | 100 | 3 | 1150 | 1152 | 0.2 | 13.91 | 176.95 | 789.47 | 1676.7 | 25.2 | 36 | 49.4 | 0 | 0 | 0 |
| **range-any-room median** | 100 | - | **958** | | | | | **720.03** | | | | | | | |

