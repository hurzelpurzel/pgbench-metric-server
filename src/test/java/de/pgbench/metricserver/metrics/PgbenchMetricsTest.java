package de.pgbench.metricserver.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.pgbench.metricserver.config.PgbenchProperties;
import de.pgbench.metricserver.model.PgbenchMeasurements;
import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.model.PgbenchStatus;
import de.pgbench.metricserver.service.PgbenchResultStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgbenchMetricsTest {

    private SimpleMeterRegistry registry;
    private PgbenchResultStore store;
    private PgbenchMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        store = new PgbenchResultStore(new PgbenchProperties());
        metrics = new PgbenchMetrics(registry, store);
    }

    @Test
    @DisplayName("reports NaN before the first run")
    void reportsNoDataBeforeTheFirstRun() {
        assertThat(registry.get(PgbenchMetrics.TPS).gauge().value()).isNaN();
        assertThat(registry.get(PgbenchMetrics.LAST_RUN_SUCCESSFUL).gauge().value()).isNaN();
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "success").counter().count())
                .isZero();
    }

    @Test
    @DisplayName("publishes the measurements of the last run")
    void publishesMeasurements() {
        PgbenchRun run = successfulRun();
        store.record(run);
        metrics.recordRun(run);

        assertThat(registry.get(PgbenchMetrics.TPS).gauge().value()).isEqualTo(4500.5, within(1e-9));
        assertThat(registry.get(PgbenchMetrics.LATENCY_AVERAGE).gauge().value()).isEqualTo(0.222, within(1e-9));
        assertThat(registry.get(PgbenchMetrics.LATENCY_STDDEV).gauge().value()).isEqualTo(0.1, within(1e-9));
        assertThat(registry.get(PgbenchMetrics.CONNECTION_TIME).gauge().value()).isEqualTo(3.5, within(1e-9));
        assertThat(registry.get(PgbenchMetrics.TRANSACTIONS_PROCESSED).gauge().value()).isEqualTo(45005.0);
        assertThat(registry.get(PgbenchMetrics.FAILED_TRANSACTIONS).gauge().value()).isEqualTo(3.0);
        assertThat(registry.get(PgbenchMetrics.FAILED_TRANSACTIONS_PERCENT).gauge().value())
                .isEqualTo(0.007, within(1e-9));
        assertThat(registry.get(PgbenchMetrics.CLIENTS).gauge().value()).isEqualTo(4.0);
        assertThat(registry.get(PgbenchMetrics.THREADS).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(PgbenchMetrics.SCALING_FACTOR).gauge().value()).isEqualTo(10.0);
        assertThat(registry.get(PgbenchMetrics.RUN_DURATION).gauge().value()).isEqualTo(10.0);
        assertThat(registry.get(PgbenchMetrics.LAST_RUN_SUCCESSFUL).gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(PgbenchMetrics.LAST_RUN_WALL_CLOCK).gauge().value()).isEqualTo(10.2, within(1e-9));
        assertThat(registry.get(PgbenchMetrics.LAST_RUN_TIMESTAMP).gauge().value())
                .isEqualTo(Instant.parse("2026-01-02T03:04:05Z").getEpochSecond());
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("reports NaN for measurements a run did not produce")
    void reportsNaNForMissingMeasurements() {
        PgbenchRun run = PgbenchRun.error(Instant.now(), "connection failed", "pgbench -h localhost");
        store.record(run);
        metrics.recordRun(run);

        assertThat(registry.get(PgbenchMetrics.TPS).gauge().value()).isNaN();
        assertThat(registry.get(PgbenchMetrics.LATENCY_AVERAGE).gauge().value()).isNaN();
        assertThat(registry.get(PgbenchMetrics.LAST_RUN_SUCCESSFUL).gauge().value()).isEqualTo(0.0);
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "error").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("exposes the client and server version as labels")
    void exposesVersions() {
        PgbenchRun run = successfulRun();
        store.record(run);
        metrics.recordRun(run);

        assertThat(versionGauges()).contains("client:17.2", "server:16.4");
    }

    @Test
    @DisplayName("replaces the version labels when the version changes")
    void replacesVersionLabels() {
        PgbenchRun first = successfulRun();
        store.record(first);
        metrics.recordRun(first);
        assertThat(versionGauges()).contains("client:17.2", "server:16.4");

        PgbenchMeasurements upgraded = PgbenchMeasurements.builder()
                .clientVersion("18.0")
                .serverVersion("18.0")
                .transactionsProcessed(10L)
                .tps(10.0)
                .latencyAverageMs(1.0)
                .build();
        PgbenchRun second = PgbenchRun.of(
                Instant.now(), Instant.now(), 0, PgbenchStatus.SUCCESS, null, "pgbench", upgraded);
        store.record(second);
        metrics.recordRun(second);

        assertThat(versionGauges()).contains("client:18.0", "server:18.0").doesNotContain("client:17.2", "server:16.4");
    }

    private java.util.List<String> versionGauges() {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(PgbenchMetrics.INFO))
                .map(meter -> meter.getId().getTag("component") + ":" + meter.getId().getTag("version"))
                .toList();
    }

    private PgbenchRun successfulRun() {
        PgbenchMeasurements measurements = PgbenchMeasurements.builder()
                .clientVersion("17.2")
                .serverVersion("16.4")
                .transactionType("<builtin: TPC-B (sort of)>")
                .scalingFactor(10)
                .queryMode("simple")
                .clients(4)
                .threads(1)
                .transactionsProcessed(45005L)
                .failedTransactions(3L)
                .failedTransactionsPercent(0.007)
                .latencyAverageMs(0.222)
                .latencyStddevMs(0.1)
                .tps(4500.5)
                .connectionTimeMs(3.5)
                .reportedDurationSeconds(10)
                .build();
        return PgbenchRun.of(
                Instant.parse("2026-01-02T03:04:05Z"),
                Instant.parse("2026-01-02T03:04:15.200Z"),
                0,
                PgbenchStatus.SUCCESS,
                null,
                "pgbench -c 4 -T 10",
                measurements);
    }

    @Test
    @DisplayName("uses a wall clock duration of the process")
    void derivesWallClock() {
        PgbenchRun run = successfulRun();
        assertThat(run.wallClock()).isEqualTo(Duration.ofMillis(10200));
    }

    @Test
    @DisplayName("keeps a counter per status and gauges for the measurements")
    void registersMeters() {
        assertThat(registry.getMeters())
                .filteredOn(meter -> PgbenchMetrics.RUNS_TOTAL.equals(meter.getId().getName()))
                .map(meter -> meter.getId().getTag("status"))
                .containsExactlyInAnyOrder("success", "failed", "timeout", "error");
        assertThat(registry.getMeters())
                .filteredOn(meter -> !PgbenchMetrics.RUNS_TOTAL.equals(meter.getId().getName()))
                .isNotEmpty()
                .allSatisfy(meter -> assertThat(meter).isInstanceOf(Gauge.class));
    }
}
