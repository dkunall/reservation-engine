# Benchmark run 2026-10-20T17:05:47Z

- CPU: 8 vCPU, Intel(R) Xeon(R) Processor
- MemTotal: 16386368 kB
- JVM: OpenJDK 64-Bit Server VM 21.0.12.1, max heap 2048 MB
- PostgreSQL: PostgreSQL 17.11 (Debian 17.11-0+deb13u1) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
- PG settings: {max_connections=100, shared_buffers=128MB, synchronous_commit=on, fsync=on, wal_level=replica}
- Hikari maximum-pool-size: 20; lock_timeout 5s (application default)
- Warm-up 5 s, then 3 measured runs x 10 s per configuration

## (a2) Flash-sale sell-out: 20000 units, 200 threads, qty 1, until sold out

| layout | run | sold | oversold | 409s while selling | sell-out time s | sold/s | p50 ms | p95 ms | p99 ms | deadlocks | errors |
|---|---|---|---|---|---|---|---|---|---|---|---|
| single-row | 1 | 20000 | 0 | 200 | 20.54 | 974 | 129.19 | 753.47 | 1308.26 | 0 | 0 |
| single-row | 2 | 20000 | 0 | 200 | 15.06 | 1328 | 126.45 | 519.77 | 844.65 | 0 | 0 |
| single-row | 3 | 20000 | 0 | 200 | 14.40 | 1389 | 17.82 | 692.38 | 1349.15 | 0 | 0 |
| sharded-16 | 1 | 20000 | 0 | 200 | 3.28 | 6096 | 3.67 | 145.67 | 228.94 | 0 | 0 |
| sharded-16 | 2 | 20000 | 0 | 200 | 3.47 | 5766 | 3.74 | 157.03 | 248.83 | 0 | 0 |
| sharded-16 | 3 | 20000 | 0 | 200 | 5.61 | 3568 | 5.67 | 279.71 | 521.06 | 0 | 0 |
| sharded-64 | 1 | 20000 | 0 | 200 | 3.78 | 5296 | 4.16 | 181.55 | 297.08 | 0 | 0 |
| sharded-64 | 2 | 20000 | 0 | 200 | 3.27 | 6114 | 3.58 | 150.34 | 234.32 | 0 | 0 |
| sharded-64 | 3 | 20000 | 0 | 200 | 3.47 | 5763 | 3.66 | 160.19 | 254.75 | 0 | 0 |

(409s while selling = requests rejected because stock was exhausted at the moment they ran; each thread stops at its first 409.)

