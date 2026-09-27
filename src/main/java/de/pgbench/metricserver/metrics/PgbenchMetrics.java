package de.pgbench.metricserver.metrics;

import de.pgbench.metricserver.model.PgbenchMeasurements;
import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.model.PgbenchStatus;
import de.pgbench.metricserver.service.PgbenchResultStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * Publishes the result of the last pgbench run as Prometheus metrics.
 *
 * <p>Every measurement is a gauge backed by the result store. As long as no run has produced a
 * value the gauge reports {@code NaN}, so a missing measurement is not mistaken for a measurement
 * of zero. {@code pgbench_runs_total} and {@code pgbench_last_run_successful} tell whether a run
 * has happened at all and whether it worked.
 */
@Component
public class PgbenchMetrics {

    public static final String RUNS_TOTAL = "pgbench.runs";
    public static final String LAST_RUN_SUCCESSFUL = "pgbench.last_run.successful";
    public static final String LAST_RUN_TIMESTAMP = "pgbench.last_run.timestamp";
    public static final String LAST_RUN_WALL_CLOCK = "pgbench.last_run.wall.clock";
    public static final String TPS = "pgbench.tps";
    public static final String LATENCY_AVERAGE = "pgbench.latency.average";
    public static final String LATENCY_STDDEV = "pgbench.latency.stddev";
    public static final String CONNECTION_TIME = "pgbench.connection.time";
    public static final String TRANSACTIONS_PROCESSED = "pgbench.transactions.processed";
    public static final String FAILED_TRANSACTIONS = "pgbench.transactions.failed";
    public static final String FAILED_TRANSACTIONS_PERCENT = "pgbench.transactions.failed.percent";
    public static final String CLIENTS = "pgbench.clients";
    public static final String THREADS = "pgbench.threads";
    public static final String SCALING_FACTOR = "pgbench.scaling.factor";
    public static final String RUN_DURATION = "pgbench.run.duration";
    public static final String INFO = "pgbench.info";

    private static final double INFO_VALUE = 1.0;

    private final MeterRegistry registry;
    private final PgbenchResultStore store;
    private final Map<PgbenchStatus, Counter> runsByStatus = new EnumMap<>(PgbenchStatus.class);

    private volatile Meter clientInfoMeter;
    private volatile Meter serverInfoMeter;
    private volatile String clientVersion = "unknown";
    private volatile String serverVersion = "unknown";

    public PgbenchMetrics(MeterRegistry registry, PgbenchResultStore store) {
        this.registry = registry;
        this.store = store;
        for (PgbenchStatus status : PgbenchStatus.values()) {
            runsByStatus.put(
                    status,
                    Counter.builder(RUNS_TOTAL)
                            .description("Finished pgbench runs by status")
                            .tag("status", status.name().toLowerCase())
                            .register(registry));
        }

        Gauge.builder(LAST_RUN_SUCCESSFUL, store, resultStore -> number(resultStore, run -> run.isSuccessful() ? 1 : 0))
                .description("1 when the last pgbench run succeeded, 0 otherwise")
                .register(registry);
        Gauge.builder(LAST_RUN_TIMESTAMP, store, resultStore -> epochSeconds(resultStore))
                .description("Start time of the last pgbench run")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(
                        LAST_RUN_WALL_CLOCK,
                        store,
                        resultStore -> number(resultStore, run -> run.wallClock().toMillis() / 1000.0))
                .description("Wall clock time the last pgbench process needed")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(TPS, store, resultStore -> decimal(resultStore, PgbenchMeasurements::tps))
                .description("Transactions per second of the last pgbench run")
                .register(registry);
        Gauge.builder(LATENCY_AVERAGE, store, resultStore -> decimal(resultStore, PgbenchMeasurements::latencyAverageMs))
                .description("Average transaction latency of the last pgbench run")
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder(LATENCY_STDDEV, store, resultStore -> decimal(resultStore, PgbenchMeasurements::latencyStddevMs))
                .description("Standard deviation of the transaction latency of the last pgbench run")
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder(CONNECTION_TIME, store, resultStore -> decimal(resultStore, PgbenchMeasurements::connectionTimeMs))
                .description("Initial connection time of the last pgbench run")
                .baseUnit("milliseconds")
                .register(registry);
        Gauge.builder(
                        TRANSACTIONS_PROCESSED,
                        store,
                        resultStore -> boxed(resultStore, PgbenchMeasurements::transactionsProcessed))
                .description("Transactions processed by the last pgbench run")
                .register(registry);
        Gauge.builder(FAILED_TRANSACTIONS, store, resultStore -> boxed(resultStore, PgbenchMeasurements::failedTransactions))
                .description("Transactions that failed during the last pgbench run")
                .register(registry);
        Gauge.builder(
                        FAILED_TRANSACTIONS_PERCENT,
                        store,
                        resultStore -> decimal(resultStore, PgbenchMeasurements::failedTransactionsPercent))
                .description("Share of failed transactions of the last pgbench run")
                .baseUnit("percent")
                .register(registry);
        Gauge.builder(CLIENTS, store, resultStore -> boxed(resultStore, PgbenchMeasurements::clients))
                .description("Concurrent database sessions of the last pgbench run")
                .register(registry);
        Gauge.builder(THREADS, store, resultStore -> boxed(resultStore, PgbenchMeasurements::threads))
                .description("pgbench worker threads of the last pgbench run")
                .register(registry);
        Gauge.builder(SCALING_FACTOR, store, resultStore -> boxed(resultStore, PgbenchMeasurements::scalingFactor))
                .description("Scale factor of the last pgbench run")
                .register(registry);
        Gauge.builder(
                        RUN_DURATION,
                        store,
                        resultStore -> boxed(resultStore, PgbenchMeasurements::reportedDurationSeconds))
                .description("Benchmark duration of the last pgbench run")
                .baseUnit("seconds")
                .register(registry);
    }

    /** Counts a finished run and keeps the version labels of {@link #INFO} up to date. */
    public void recordRun(PgbenchRun run) {
        Counter counter = runsByStatus.get(run.status());
        if (counter != null) {
            counter.increment();
        }
        if (run.measurements() != null) {
            updateInfo(run.measurements());
        }
    }

    private void updateInfo(PgbenchMeasurements measurements) {
        String newClientVersion = Objects.requireNonNullElse(measurements.clientVersion(), "unknown");
        String newServerVersion = Objects.requireNonNullElse(measurements.serverVersion(), "unknown");
        if (newClientVersion.equals(clientVersion) && newServerVersion.equals(serverVersion)) {
            return;
        }
        clientVersion = newClientVersion;
        serverVersion = newServerVersion;
        clientInfoMeter = replaceInfo(
                clientInfoMeter,
                Gauge.builder(INFO, store, resultStore -> INFO_VALUE)
                        .description("Version of the pgbench client and the benchmarked server")
                        .tag("component", "client")
                        .tag("version", clientVersion)
                        .register(registry));
        serverInfoMeter = replaceInfo(
                serverInfoMeter,
                Gauge.builder(INFO, store, resultStore -> INFO_VALUE)
                        .description("Version of the pgbench client and the benchmarked server")
                        .tag("component", "server")
                        .tag("version", serverVersion)
                        .register(registry));
    }

    private Meter replaceInfo(Meter previous, Meter replacement) {
        if (previous != null) {
            registry.remove(previous);
        }
        return replacement;
    }

    private static double number(PgbenchResultStore store, ToDoubleFunction<PgbenchRun> function) {
        return store.latest()
                .map(run -> sanitize(function.applyAsDouble(run)))
                .orElse(Double.NaN);
    }

    private static double decimal(PgbenchResultStore store, ToDoubleFunction<PgbenchMeasurements> function) {
        return store.latest()
                .map(PgbenchRun::measurements)
                .map(measurements -> sanitize(function.applyAsDouble(measurements)))
                .orElse(Double.NaN);
    }

    private static double boxed(PgbenchResultStore store, Function<PgbenchMeasurements, ? extends Number> function) {
        return store.latest()
                .map(PgbenchRun::measurements)
                .map(measurements -> {
                    Number value = function.apply(measurements);
                    return value == null ? Double.NaN : sanitize(value.doubleValue());
                })
                .orElse(Double.NaN);
    }

    private static double sanitize(double value) {
        return Double.isFinite(value) ? value : Double.NaN;
    }

    private static double epochSeconds(PgbenchResultStore store) {
        return number(store, run -> run.startedAt() == null ? Double.NaN : run.startedAt().getEpochSecond());
    }
}
