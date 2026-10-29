#!/usr/bin/env bash
# Reproducible load benchmark against a LOCAL PostgreSQL (default localhost:5432, user postgres/postgres,
# database reservation_bench - created automatically). Results: stdout + target/benchmark/results-*.md
#
#   scripts/run-benchmark.sh                 # full suite, Hikari pool 20 (the application default)
#   POOLS="20 50" scripts/run-benchmark.sh   # repeat the suite per pool size
#   THREADS=50,100 SCENARIOS=capacity RUN_SEC=10 RUNS=3 scripts/run-benchmark.sh
#
# Local throwaway credentials only; override with BENCH_DB_URL / BENCH_DB_USER / BENCH_DB_PASSWORD.
set -euo pipefail
cd "$(dirname "$0")/.."

POOLS=${POOLS:-20}
THREADS=${THREADS:-50,100,200}
SCENARIOS=${SCENARIOS:-capacity,sellout,itinerary,range}
WARMUP_SEC=${WARMUP_SEC:-5}
RUN_SEC=${RUN_SEC:-10}
RUNS=${RUNS:-3}
BENCH_DB_URL=${BENCH_DB_URL:-jdbc:postgresql://localhost:5432/reservation_bench}
BENCH_DB_USER=${BENCH_DB_USER:-postgres}
BENCH_DB_PASSWORD=${BENCH_DB_PASSWORD:-postgres}

BENCH_PG_PORT=$(sed -E "s|.*:([0-9]+)/.*|\1|" <<<"$BENCH_DB_URL")
if command -v pg_isready >/dev/null && ! pg_isready -h localhost -p "$BENCH_PG_PORT" -q; then
  echo "PostgreSQL is not accepting connections on localhost:$BENCH_PG_PORT. Start it first, e.g.:" >&2
  echo "  sudo pg_ctlcluster 17 main start   (or: docker compose up -d postgres)" >&2
  exit 1
fi

mvn -B -q -DskipTests test-compile
for pool in $POOLS; do
  echo "=== Hikari pool ${pool} ==="
  mvn -B -q -Pbenchmark exec:exec \
    -Dbench.pool="$pool" -Dbench.threads="$THREADS" -Dbench.scenarios="$SCENARIOS" \
    -Dbench.warmupSec="$WARMUP_SEC" -Dbench.runSec="$RUN_SEC" -Dbench.runs="$RUNS" \
    -Dbench.db.url="$BENCH_DB_URL" -Dbench.db.user="$BENCH_DB_USER" -Dbench.db.password="$BENCH_DB_PASSWORD"
done
