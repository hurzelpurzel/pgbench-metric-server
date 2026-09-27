package de.pgbench.metricserver.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.pgbench.metricserver.config.PgbenchCommandBuilder;
import de.pgbench.metricserver.config.PgbenchProperties;
import de.pgbench.metricserver.metrics.PgbenchMetrics;
import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.model.PgbenchStatus;
import de.pgbench.metricserver.parse.PgbenchOutputParser;
import de.pgbench.metricserver.process.ProcessOutcome;
import de.pgbench.metricserver.process.ProcessRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgbenchServiceTest {

    private static final String SUCCESS_OUTPUT = """
            pgbench (17.2, server 17.2)
            transaction type: <builtin: TPC-B (sort of)>
            scaling factor: 1
            query mode: simple
            number of clients: 4
            number of threads: 1
            duration: 10 s
            number of transactions actually processed: 40000
            number of failed transactions: 0 (0.000%)
            latency average = 1.000 ms
            latency stddev = 0.500 ms
            initial connection time = 5.000 ms
            tps = 4000.000000 (without initial connection time)
            """;

    private PgbenchProperties properties;
    private SimpleMeterRegistry registry;
    private PgbenchResultStore store;
    private PgbenchMetrics metrics;
    private Executor executor;
    private List<String> lastCommand;
    private Map<String, String> lastEnvironment;

    @BeforeEach
    void setUp() {
        properties = new PgbenchProperties();
        properties.getWorkload().setBinary("/usr/bin/pgbench");
        properties.getConnection().setPassword("secret");
        registry = new SimpleMeterRegistry();
        store = new PgbenchResultStore(properties);
        metrics = new PgbenchMetrics(registry, store);
        executor = Runnable::run;
    }

    @Test
    @DisplayName("records a successful run with the parsed measurements")
    void recordsSuccessfulRun() {
        PgbenchService service = service(exitCode(0, SUCCESS_OUTPUT));

        PgbenchRun run = service.run();

        assertThat(run.status()).isEqualTo(PgbenchStatus.SUCCESS);
        assertThat(run.isSuccessful()).isTrue();
        assertThat(run.exitCode()).isZero();
        assertThat(run.error()).isNull();
        assertThat(run.wallClock()).isPositive();
        assertThat(run.measurements().tps()).isEqualTo(4000.0);
        assertThat(run.measurements().transactionsProcessed()).isEqualTo(40000L);
        assertThat(lastCommand).contains("/usr/bin/pgbench", "-T", "10");
        assertThat(lastEnvironment).containsEntry("PGPASSWORD", "secret");
        assertThat(service.latestRun()).contains(run);
        assertThat(registry.get(PgbenchMetrics.TPS).gauge().value()).isEqualTo(4000.0);
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("keeps partial results of a run that exited with 2")
    void recordsPartialResultsOfFailedRun() {
        String output = SUCCESS_OUTPUT + "pgbench: error: duplicate key value violates unique constraint\n";
        PgbenchService service = service(exitCode(2, output));

        PgbenchRun run = service.run();

        assertThat(run.status()).isEqualTo(PgbenchStatus.FAILED);
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.error()).contains("duplicate key value");
        assertThat(run.measurements().tps()).isEqualTo(4000.0);
        assertThat(registry.get(PgbenchMetrics.LAST_RUN_SUCCESSFUL).gauge().value()).isZero();
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "failed").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("reports a connection failure without measurements")
    void reportsConnectionFailure() {
        String output = "pgbench: error: connection to server at \"localhost\" (127.0.0.1),"
                + " port 5432 failed: Connection refused\n";
        PgbenchService service = service(exitCode(1, output));

        PgbenchRun run = service.run();

        assertThat(run.status()).isEqualTo(PgbenchStatus.FAILED);
        assertThat(run.error()).contains("Connection refused");
        assertThat(run.measurements().tps()).isNull();
        assertThat(registry.get(PgbenchMetrics.TPS).gauge().value()).isNaN();
    }

    @Test
    @DisplayName("marks a run as timed out when the process was killed")
    void reportsTimeout() {
        PgbenchService service = service(
                (command, environment, timeout) ->
                        new ProcessOutcome(-1, "progress: 1.0 s, 1.0 tps", Duration.ofSeconds(90), true));

        PgbenchRun run = service.run();

        assertThat(run.status()).isEqualTo(PgbenchStatus.TIMEOUT);
        assertThat(run.error()).isEqualTo("pgbench exited with code -1");
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "timeout").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("reports a binary that cannot be started")
    void reportsUnstartableProcess() {
        ProcessRunner failing = (command, environment, timeout) -> {
            throw new IOException("Cannot run program \"/usr/bin/pgbench\"");
        };
        PgbenchService service = service(failing);

        PgbenchRun run = service.run();

        assertThat(run.status()).isEqualTo(PgbenchStatus.ERROR);
        assertThat(run.exitCode()).isEqualTo(-1);
        assertThat(run.error()).contains("Cannot run program");
        assertThat(registry.find(PgbenchMetrics.RUNS_TOTAL).tag("status", "error").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("accepts only one background run at a time")
    void rejectsConcurrentTriggers() throws Exception {
        var gate = new CountDownLatch(1);
        ProcessRunner blocking = (command, environment, timeout) -> {
            gate.await(10, TimeUnit.SECONDS);
            return new ProcessOutcome(0, SUCCESS_OUTPUT, Duration.ofSeconds(10), false);
        };
        var backgroundExecutor = Executors.newSingleThreadExecutor();
        try {
            PgbenchService service = new PgbenchService(
                    properties, new PgbenchCommandBuilder(), blocking, new PgbenchOutputParser(), store, metrics,
                    backgroundExecutor);

            assertThat(service.trigger()).isTrue();
            assertThat(service.isRunning()).isTrue();
            assertThat(service.trigger()).isFalse();

            gate.countDown();
            awaitIdle(service);
            assertThat(service.isRunning()).isFalse();
            assertThat(service.trigger()).isTrue();
            awaitIdle(service);
        } finally {
            backgroundExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("passes the configured timeout to the process runner")
    void passesTimeout() {
        Duration[] seen = new Duration[1];
        ProcessRunner inspecting = (command, environment, timeout) -> {
            seen[0] = timeout;
            return new ProcessOutcome(0, SUCCESS_OUTPUT, Duration.ofSeconds(1), false);
        };
        properties.setTimeout(Duration.ofSeconds(42));

        service(inspecting).run();

        assertThat(seen[0]).isEqualTo(Duration.ofSeconds(42));
    }

    private PgbenchService service(ProcessRunner processRunner) {
        return new PgbenchService(
                properties, new PgbenchCommandBuilder(), processRunner, new PgbenchOutputParser(), store, metrics,
                executor);
    }

    private ProcessRunner exitCode(int exitCode, String output) {
        return (command, environment, timeout) -> {
            lastCommand = command;
            lastEnvironment = environment;
            return new ProcessOutcome(exitCode, output, Duration.ofSeconds(10), false);
        };
    }

    private void awaitIdle(PgbenchService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("returns a rejected trigger when the executor is shut down")
    void rejectsTriggerAfterShutdown() {
        var executorService = Executors.newSingleThreadExecutor();
        executorService.shutdown();
        PgbenchService service = new PgbenchService(
                properties,
                new PgbenchCommandBuilder(),
                (command, environment, timeout) ->
                        new ProcessOutcome(0, SUCCESS_OUTPUT, Duration.ofSeconds(1), false),
                new PgbenchOutputParser(),
                store,
                metrics,
                executorService);

        assertThat(service.trigger()).isFalse();
        assertThat(service.isRunning()).isFalse();
    }
}
