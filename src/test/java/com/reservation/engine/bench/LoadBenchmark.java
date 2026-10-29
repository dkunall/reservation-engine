package com.reservation.engine.bench;

import com.reservation.engine.ReservationEngineApplication;
import com.reservation.engine.application.CurrentUser;
import com.reservation.engine.application.ItineraryService;
import com.reservation.engine.application.ItineraryView;
import com.reservation.engine.application.LockConflictRetry;
import com.reservation.engine.application.ReservationService;
import com.reservation.engine.application.ReservationView;
import com.reservation.engine.application.ResourceService;
import com.reservation.engine.domain.AllocationRequest;
import com.reservation.engine.domain.Money;
import com.reservation.engine.domain.exception.InventoryUnavailableException;
import com.reservation.engine.inventory.spi.InventorySetup;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Reproducible load benchmark against a REAL local PostgreSQL. Boots the full application context (same beans,
 * same transaction boundaries, same Hikari pool) and drives the application services directly from N client
 * threads - i.e. it measures the engine + database, not HTTP/JSON/JWT overhead.
 *
 * <p>Per configuration: fresh resources, warm-up (not recorded), then R measured runs of D seconds each.
 * Recorded: successful ops/s, attempted ops/s, latency p50/p95/p99/max of the measured operation (hold),
 * conflict (=HTTP 409) rate, unexpected errors, sessions waiting on locks (sampled every 100 ms from
 * pg_stat_activity), deadlocks (pg_stat_database delta), threads waiting for a pooled connection (Hikari MXBean),
 * whole-transaction retries (itineraries).
 *
 * <p>Run: {@code scripts/run-benchmark.sh} (see docs/PERFORMANCE.md). Options (system properties):
 * bench.pool (Hikari max pool, default 20), bench.warmupSec (5), bench.runSec (10), bench.runs (3),
 * bench.scenarios (capacity,sellout,itinerary,range), bench.threads (50,100,200), bench.db.url/user/password.
 */
public final class LoadBenchmark {

    private static final String DB_URL = System.getProperty("bench.db.url", "jdbc:postgresql://localhost:5432/reservation_bench");
    private static final String DB_USER = System.getProperty("bench.db.user", "postgres");
    private static final String DB_PASSWORD = System.getProperty("bench.db.password", "postgres");
    private static final int POOL = Integer.getInteger("bench.pool", 20);
    private static final int WARMUP = Integer.getInteger("bench.warmupSec", 5);
    private static final int RUN = Integer.getInteger("bench.runSec", 10);
    private static final int RUNS = Integer.getInteger("bench.runs", 3);
    private static final List<String> SCENARIOS = List.of(System.getProperty("bench.scenarios", "capacity,sellout,itinerary,range").split(","));
    private static final int[] THREADS = Arrays.stream(System.getProperty("bench.threads", "50,100,200").split(","))
            .mapToInt(Integer::parseInt).toArray();

    private ConfigurableApplicationContext ctx;
    private JdbcTemplate jdbc;
    private ReservationService reservations;
    private ItineraryService itineraries;
    private ResourceService resources;
    private HikariDataSource pool;
    private final StringBuilder report = new StringBuilder();

    /** One measured interval. */
    record RunResult(long ok, long conflicts, long errors, double seconds, long[] latNanos, double avgLockWaiters,
                     int maxLockWaiters, long deadlocks, double avgPoolWaiters, long retries, String firstError) {
        double okPerSec() { return ok / seconds; }
        double attemptsPerSec() { return (ok + conflicts + errors) / seconds; }
        double conflictPct() { long n = ok + conflicts + errors; return n == 0 ? 0 : 100.0 * conflicts / n; }
        double pct(double p) {
            if (latNanos.length == 0) return Double.NaN;
            int idx = (int) Math.min(latNanos.length - 1, Math.ceil(p / 100.0 * latNanos.length) - 1);
            return latNanos[Math.max(0, idx)] / 1e6;
        }
        double max() { return latNanos.length == 0 ? Double.NaN : latNanos[latNanos.length - 1] / 1e6; }
    }

    /** Outcome of one operation; latency is measured by the harness around {@link Op#run}. */
    enum Outcome { OK, CONFLICT }

    @FunctionalInterface
    interface Op {
        Outcome run(int thread) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        new LoadBenchmark().run();
    }

    private void run() throws Exception {
        ensureDatabase();
        boot();
        header();
        try {
            for (String s : SCENARIOS) {
                switch (s.trim()) {
                    case "capacity" -> capacityScenario();
                    case "sellout" -> selloutScenario();
                    case "itinerary" -> itineraryScenario();
                    case "range" -> rangeScenario();
                    default -> throw new IllegalArgumentException("unknown scenario " + s);
                }
            }
        } finally {
            Path out = Path.of("target", "benchmark", "results-pool" + POOL + "-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report.toString());
            System.out.println("\nResults written to " + out.toAbsolutePath());
            ctx.close();
        }
    }

    // ------------------------------------------------------------------ setup

    private void ensureDatabase() throws Exception {
        String adminUrl = DB_URL.substring(0, DB_URL.lastIndexOf('/')) + "/postgres";
        String db = DB_URL.substring(DB_URL.lastIndexOf('/') + 1);
        try (Connection c = DriverManager.getConnection(adminUrl, DB_USER, DB_PASSWORD); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + db + "'");
            if (!rs.next()) {
                st.execute("CREATE DATABASE " + db);
            }
        }
    }

    private void boot() {
        byte[] secret = new byte[48];
        new SecureRandom().nextBytes(secret); // throwaway per-run HMAC key; no tokens are issued
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", DB_URL);
        props.put("spring.datasource.username", DB_USER);
        props.put("spring.datasource.password", DB_PASSWORD);
        props.put("spring.datasource.hikari.maximum-pool-size", POOL);
        props.put("spring.datasource.hikari.minimum-idle", POOL);
        props.put("spring.datasource.hikari.register-mbeans", true);
        props.put("reservation.jwt.secret", Base64.getEncoder().encodeToString(secret));
        props.put("reservation.scheduling.enabled", false);
        props.put("server.port", 0);
        props.put("logging.level.root", "WARN");
        props.put("logging.level.com.reservation.engine", "ERROR");
        // system properties outrank application.yml (default properties would not)
        props.forEach((k, v) -> System.setProperty(k, String.valueOf(v)));
        SpringApplication app = new SpringApplication(ReservationEngineApplication.class);
        app.setLogStartupInfo(false);
        ctx = app.run();
        jdbc = ctx.getBean(JdbcTemplate.class);
        reservations = ctx.getBean(ReservationService.class);
        itineraries = ctx.getBean(ItineraryService.class);
        resources = ctx.getBean(ResourceService.class);
        pool = ctx.getBean(HikariDataSource.class);
    }

    private void truncate() {
        jdbc.execute("""
                TRUNCATE idempotency_record, outbox_event, payment_transaction, reservation_audit,
                         waitlist_signal, waitlist_entry,
                         range_allocation, slot_allocation, unit_allocation, capacity_allocation, capacity_bucket,
                         time_slot, inventory_unit, capacity_inventory, reservation, itinerary, resource
                RESTART IDENTITY CASCADE
                """);
        jdbc.execute("VACUUM ANALYZE");
    }

    private UUID resource(String type, String name, InventorySetup inv) {
        return resources.create(new ResourceService.CreateResourceCommand(type, name, null, Map.of(),
                Money.of("100.00", "INR"), inv)).getId();
    }

    private void header() throws Exception {
        String cpu = Files.readAllLines(Path.of("/proc/cpuinfo")).stream().filter(l -> l.startsWith("model name"))
                .findFirst().map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse("?");
        String mem = Files.readAllLines(Path.of("/proc/meminfo")).get(0).replaceAll("\\s+", " ");
        String pg = jdbc.queryForObject("SELECT version()", String.class);
        Map<String, String> settings = new LinkedHashMap<>();
        for (String s : List.of("max_connections", "shared_buffers", "synchronous_commit", "fsync", "wal_level")) {
            settings.put(s, jdbc.queryForObject("SHOW " + s, String.class));
        }
        line("# Benchmark run " + Instant.now().truncatedTo(ChronoUnit.SECONDS));
        line("");
        line("- CPU: " + Runtime.getRuntime().availableProcessors() + " vCPU, " + cpu);
        line("- " + mem);
        line("- JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version")
                + ", max heap " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");
        line("- PostgreSQL: " + pg);
        line("- PG settings: " + settings);
        line("- Hikari maximum-pool-size: " + POOL + "; lock_timeout 5s (application default)");
        line("- Warm-up " + WARMUP + " s, then " + RUNS + " measured runs x " + RUN + " s per configuration");
        line("");
    }

    // ------------------------------------------------------------------ scenarios

    /** (a) Hot CAPACITY row vs sharded buckets; capacity is effectively unlimited so this isolates contention. */
    private void capacityScenario() throws Exception {
        line("## (a) CAPACITY hold throughput: single row vs sharded buckets (no sell-out, qty 1)");
        line("");
        tableHeader("layout");
        for (int threads : THREADS) {
            for (Integer buckets : new Integer[]{null, 16, 64}) {
                String label = buckets == null ? "single-row" : "sharded-" + buckets;
                List<RunResult> runs = measure(() -> {
                    UUID r = resource("EVENT_GA", label, new InventorySetup(50_000_000, null, null, buckets));
                    AllocationRequest req = new AllocationRequest(1, List.of(), null, null, null);
                    return t -> {
                        reservations.hold(CurrentUser.user("u" + t), new ReservationService.HoldCommand(r, req));
                        return Outcome.OK;
                    };
                }, threads);
                tableRows(label, threads, runs);
            }
        }
        line("");
    }

    /** (a2) Flash sale sell-out: limited stock, everybody hammers it; correctness + time to sell out. */
    private void selloutScenario() throws Exception {
        int stock = 20_000;
        int threads = THREADS[THREADS.length - 1];
        line("## (a2) Flash-sale sell-out: " + stock + " units, " + threads + " threads, qty 1, until sold out");
        line("");
        line("| layout | run | sold | oversold | 409s while selling | sell-out time s | sold/s | p50 ms | p95 ms | p99 ms | deadlocks | errors |");
        line("|---|---|---|---|---|---|---|---|---|---|---|---|");
        for (Integer buckets : new Integer[]{null, 16, 64}) {
            String label = buckets == null ? "single-row" : "sharded-" + buckets;
            for (int run = 0; run <= RUNS; run++) { // run 0 = warm-up
                truncate();
                UUID r = resource("EVENT_GA", label, new InventorySetup(stock, null, null, buckets));
                AllocationRequest req = new AllocationRequest(1, List.of(), null, null, null);
                long dl0 = deadlocks();
                long[][] lat = new long[threads][];
                long[] okCount = new long[threads];
                long[] conflictCount = new long[threads];
                long[] errorCount = new long[threads];
                AtomicBoolean soldOut = new AtomicBoolean();
                CountDownLatch start = new CountDownLatch(1);
                List<Thread> ts = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    final int id = t;
                    Thread th = new Thread(() -> {
                        LongList l = new LongList();
                        try {
                            start.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                        while (!soldOut.get()) {
                            long t0 = System.nanoTime();
                            try {
                                reservations.hold(CurrentUser.user("u" + id), new ReservationService.HoldCommand(r, req));
                                l.add(System.nanoTime() - t0);
                                okCount[id]++;
                            } catch (InventoryUnavailableException e) {
                                conflictCount[id]++;
                                soldOut.set(true);
                            } catch (RuntimeException e) { // e.g. pool timeout: count it, keep selling
                                errorCount[id]++;
                            }
                        }
                        lat[id] = l.toArray();
                    });
                    th.start();
                    ts.add(th);
                }
                long t0 = System.nanoTime();
                start.countDown();
                for (Thread th : ts) th.join();
                double secs = (System.nanoTime() - t0) / 1e9;
                long sold = Arrays.stream(okCount).sum();
                long conflicts = Arrays.stream(conflictCount).sum();
                long[] all = merge(lat);
                Integer dbReserved = buckets == null
                        ? jdbc.queryForObject("SELECT reserved FROM capacity_inventory WHERE resource_id = ?", Integer.class, r)
                        : jdbc.queryForObject("SELECT SUM(reserved)::int FROM capacity_bucket WHERE resource_id = ?", Integer.class, r);
                long activeAlloc = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(quantity),0) FROM capacity_allocation WHERE resource_id = ? AND status='ACTIVE'", Long.class, r);
                RunResult rr = new RunResult(sold, conflicts, 0, secs, all, 0, 0, deadlocks() - dl0, 0, 0, null);
                if (run == 0) continue;
                line(String.format("| %s | %d | %d | %d | %d | %.2f | %.0f | %.2f | %.2f | %.2f | %d | %d |", label, run, sold,
                        Math.max(0, Math.max(dbReserved, activeAlloc) - stock), conflicts, secs, sold / secs,
                        rr.pct(50), rr.pct(95), rr.pct(99), rr.deadlocks(), Arrays.stream(errorCount).sum()));
                if (sold != stock || dbReserved != stock || activeAlloc != stock) {
                    line("**INVARIANT CHECK FAILED** sold=" + sold + " db=" + dbReserved + " alloc=" + activeAlloc);
                }
            }
        }
        line("");
        line("(409s while selling = requests rejected because stock was exhausted at the moment they ran; each thread stops at its first 409.)");
        line("");
    }

    /** (b) 3-item itineraries (bus capacity + flight seat any + hotel room any) as hold -> cancel cycles. */
    private void itineraryScenario() throws Exception {
        line("## (b) Itinerary holds (3 items: BUS capacity + FLIGHT any seat + HOTEL any room; hold then cancel)");
        line("");
        line("Inventory: 60 seats, 60 rooms (same dates), unlimited bus capacity (single row, or 16 buckets). Latency = itinerary hold only.");
        line("");
        tableHeader("scenario");
        Instant in = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(30));
        for (int threads : THREADS) {
          if (threads > 100) continue;
          for (Integer busBuckets : new Integer[]{null, 16}) {
            String label = busBuckets == null ? "itinerary-3-items (bus single-row)" : "itinerary-3-items (bus sharded-16)";
            List<RunResult> runs = measure(() -> {
                UUID bus = resource("BUS", "bus", new InventorySetup(50_000_000, null, null, busBuckets));
                UUID flight = resource("FLIGHT", "flight", new InventorySetup(null, codes("S", 60), null));
                UUID hotel = resource("HOTEL_ROOM", "hotel", new InventorySetup(null, codes("R", 60), null));
                return t -> {
                    List<ItineraryService.ItemCommand> items = new ArrayList<>(List.of(
                            new ItineraryService.ItemCommand(hotel, new AllocationRequest(1, List.of(), null, in, in.plus(Duration.ofDays(2)))),
                            new ItineraryService.ItemCommand(bus, new AllocationRequest(1, List.of(), null, null, null)),
                            new ItineraryService.ItemCommand(flight, new AllocationRequest(1, List.of(), null, null, null))));
                    if ((t & 1) == 1) java.util.Collections.reverse(items); // opposite request orders on purpose
                    CurrentUser user = CurrentUser.user("u" + t);
                    try {
                        ItineraryView v = LockConflictRetry.run(3, "bench", () -> itineraries.hold(user, items));
                        markForCancel(t, () -> itineraries.cancel(user, v.id(), "bench"));
                        return Outcome.OK;
                    } catch (InventoryUnavailableException e) {
                        return Outcome.CONFLICT;
                    }
                };
            }, threads);
            tableRows(label, threads, runs);
          }
        }
        line("");
    }

    /** (c) TIME_RANGE: everybody wants any room for the same overlapping dates; hold -> cancel cycles. */
    private void rangeScenario() throws Exception {
        line("## (c) TIME_RANGE contention: any room, overlapping dates (hold then cancel)");
        line("");
        line("Inventory: 40 rooms. Each request: 2 nights starting day 30 + (0..2), so periods overlap heavily.");
        line("");
        tableHeader("scenario");
        Instant base = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(30));
        for (int threads : THREADS) {
            if (threads > 100) continue;
            List<RunResult> runs = measure(() -> {
                UUID hotel = resource("HOTEL_ROOM", "hotel", new InventorySetup(null, codes("R", 40), null));
                return t -> {
                    Instant s = base.plus(Duration.ofDays(ThreadLocalRandom.current().nextInt(3)));
                    CurrentUser user = CurrentUser.user("u" + t);
                    try {
                        ReservationView v = reservations.hold(user, new ReservationService.HoldCommand(hotel,
                                new AllocationRequest(1, List.of(), null, s, s.plus(Duration.ofDays(2)))));
                        markForCancel(t, () -> reservations.cancel(user, v.id(), "bench"));
                        return Outcome.OK;
                    } catch (InventoryUnavailableException e) {
                        return Outcome.CONFLICT;
                    }
                };
            }, threads);
            tableRows("range-any-room", threads, runs);
        }
        line("");
    }

    // ------------------------------------------------------------------ measurement core

    /** Cancel action registered by an op; run by the harness AFTER the latency sample (not timed). */
    private static final ThreadLocal<Runnable> PENDING_CANCEL = new ThreadLocal<>();

    private static void markForCancel(int thread, Supplier<?> cancel) {
        PENDING_CANCEL.set(cancel::get);
    }

    private List<RunResult> measure(Supplier<Op> setup, int threads) throws Exception {
        List<RunResult> results = new ArrayList<>();
        for (int run = 0; run <= RUNS; run++) {
            truncate();
            Op op = setup.get();
            RunResult r = drive(op, threads, run == 0 ? WARMUP : RUN);
            if (run > 0) {
                results.add(r);
            }
        }
        return results;
    }

    private RunResult drive(Op op, int threads, int seconds) throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicBoolean measuring = new AtomicBoolean();
        long[][] lat = new long[threads][];
        long[] ok = new long[threads];
        long[] conflicts = new long[threads];
        long[] errors = new long[threads];
        String[] firstError = new String[1];
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                LongList l = new LongList();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    long t0 = System.nanoTime();
                    Outcome o;
                    try {
                        o = op.run(id);
                    } catch (InventoryUnavailableException e) {
                        o = Outcome.CONFLICT;
                    } catch (Exception e) {
                        o = null;
                        synchronized (firstError) {
                            if (firstError[0] == null) firstError[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
                        }
                    }
                    long dt = System.nanoTime() - t0;
                    Runnable cancel = PENDING_CANCEL.get();
                    PENDING_CANCEL.remove();
                    if (cancel != null) {
                        try {
                            cancel.run();
                        } catch (Exception e) {
                            errors[id]++;
                        }
                    }
                    if (!measuring.get()) continue;
                    if (o == Outcome.OK) {
                        ok[id]++;
                        l.add(dt);
                    } else if (o == Outcome.CONFLICT) {
                        conflicts[id]++;
                        l.add(dt);
                    } else {
                        errors[id]++;
                    }
                }
                lat[id] = l.toArray();
            });
            th.start();
            ts.add(th);
        }
        long dl0 = deadlocks();
        long retries0 = LockConflictRetry.retriesSoFar();
        start.countDown();
        measuring.set(true);
        long t0 = System.nanoTime();
        // sample lock waiters + pool waiters every 100 ms through a dedicated (non-pooled) connection
        long samples = 0;
        long lockSum = 0;
        int lockMax = 0;
        long poolSum = 0;
        try (Connection c = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD); Statement st = c.createStatement()) {
            long end = t0 + seconds * 1_000_000_000L;
            while (System.nanoTime() < end) {
                ResultSet rs = st.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'");
                rs.next();
                int n = rs.getInt(1);
                lockSum += n;
                lockMax = Math.max(lockMax, n);
                poolSum += pool.getHikariPoolMXBean().getThreadsAwaitingConnection();
                samples++;
                Thread.sleep(100);
            }
        }
        measuring.set(false);
        double secs = (System.nanoTime() - t0) / 1e9;
        stop.set(true);
        for (Thread th : ts) th.join();
        return new RunResult(Arrays.stream(ok).sum(), Arrays.stream(conflicts).sum(), Arrays.stream(errors).sum(), secs,
                merge(lat), samples == 0 ? 0 : (double) lockSum / samples, lockMax, deadlocks() - dl0,
                samples == 0 ? 0 : (double) poolSum / samples, LockConflictRetry.retriesSoFar() - retries0, firstError[0]);
    }

    private long deadlocks() {
        Long n = jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ output

    private void tableHeader(String first) {
        line("| " + first + " | threads | run | ok ops/s | attempts/s | 409 % | p50 ms | p95 ms | p99 ms | max ms | avg lock waiters | max lock waiters | avg pool waiters | deadlocks | tx retries | errors |");
        line("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|");
    }

    private void tableRows(String label, int threads, List<RunResult> runs) {
        int i = 1;
        for (RunResult r : runs) {
            line(String.format("| %s | %d | %d | %.0f | %.0f | %.1f | %.2f | %.2f | %.2f | %.1f | %.1f | %d | %.1f | %d | %d | %d |",
                    label, threads, i++, r.okPerSec(), r.attemptsPerSec(), r.conflictPct(), r.pct(50), r.pct(95), r.pct(99),
                    r.max(), r.avgLockWaiters(), r.maxLockWaiters(), r.avgPoolWaiters(), r.deadlocks(), r.retries(), r.errors()));
            if (r.firstError() != null) {
                line("<!-- first error: " + r.firstError().replace("--", "-") + " -->");
            }
        }
        double[] okRates = runs.stream().mapToDouble(RunResult::okPerSec).sorted().toArray();
        double[] p99s = runs.stream().mapToDouble(r -> r.pct(99)).sorted().toArray();
        line(String.format("| **%s median** | %d | - | **%.0f** | | | | | **%.2f** | | | | | | | |", label, threads,
                okRates[okRates.length / 2], p99s[p99s.length / 2]));
    }

    private void line(String s) {
        System.out.println(s);
        report.append(s).append('\n');
    }

    private static List<String> codes(String prefix, int n) {
        List<String> l = new ArrayList<>();
        for (int i = 1; i <= n; i++) l.add(prefix + i);
        return l;
    }

    private static long[] merge(long[][] parts) {
        int n = 0;
        for (long[] p : parts) n += p == null ? 0 : p.length;
        long[] all = new long[n];
        int o = 0;
        for (long[] p : parts) {
            if (p == null) continue;
            System.arraycopy(p, 0, all, o, p.length);
            o += p.length;
        }
        Arrays.sort(all);
        return all;
    }

    /** Minimal growable primitive list (avoids boxing millions of samples). */
    static final class LongList {
        private long[] a = new long[1 << 14];
        private int n;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            a[n++] = v;
        }

        long[] toArray() {
            return Arrays.copyOf(a, n);
        }
    }
}
