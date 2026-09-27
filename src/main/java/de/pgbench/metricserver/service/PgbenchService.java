package de.pgbench.metricserver.service;

import de.pgbench.metricserver.config.PgbenchCommandBuilder;
import de.pgbench.metricserver.config.PgbenchProperties;
import de.pgbench.metricserver.metrics.PgbenchMetrics;
import de.pgbench.metricserver.model.PgbenchMeasurements;
import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.model.PgbenchStatus;
import de.pgbench.metricserver.parse.PgbenchOutputParser;
import de.pgbench.metricserver.process.ProcessOutcome;
import de.pgbench.metricserver.process.ProcessRunner;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Runs pgbench, parses its output and publishes the result. */
@Service
public class PgbenchService {

    private static final Logger log = LoggerFactory.getLogger(PgbenchService.class);

    private final PgbenchProperties properties;
    private final PgbenchCommandBuilder commandBuilder;
    private final ProcessRunner processRunner;
    private final PgbenchOutputParser parser;
    private final PgbenchResultStore resultStore;
    private final PgbenchMetrics metrics;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean();

    public PgbenchService(
            PgbenchProperties properties,
            PgbenchCommandBuilder commandBuilder,
            ProcessRunner processRunner,
            PgbenchOutputParser parser,
            PgbenchResultStore resultStore,
            PgbenchMetrics metrics,
            @Qualifier("pgbenchExecutor") Executor executor) {
        this.properties = properties;
        this.commandBuilder = commandBuilder;
        this.processRunner = processRunner;
        this.parser = parser;
        this.resultStore = resultStore;
        this.metrics = metrics;
        this.executor = executor;
    }

    /**
     * Starts a run in the background. At most one run is active, further triggers are rejected
     * until the running benchmark has finished.
     *
     * @return true when the run was accepted
     */
    public boolean trigger() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    runQuietly();
                } finally {
                    running.set(false);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            running.set(false);
            log.warn("pgbench run rejected, the application is shutting down");
            return false;
        }
    }

    /** Whether a pgbench run is currently in progress. */
    public boolean isRunning() {
        return running.get();
    }

    /** Executes one run and returns its result. Blocks for the duration of the benchmark. */
    public PgbenchRun run() {
        List<String> command = commandBuilder.buildCommand(properties);
        String commandLine = commandBuilder.describe(command);
        Map<String, String> environment = commandBuilder.buildEnvironment(properties);
        Instant startedAt = Instant.now();

        log.info("Running pgbench: {}", commandLine);
        ProcessOutcome outcome;
        try {
            outcome = processRunner.run(command, environment, properties.getTimeout());
        } catch (IOException e) {
            log.error("pgbench could not be started: {}", e.getMessage());
            return publish(PgbenchRun.error(startedAt, e.getMessage(), commandLine));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return publish(PgbenchRun.error(startedAt, "pgbench was interrupted", commandLine));
        }

        PgbenchRun run = toRun(startedAt, outcome, commandLine);
        return publish(run);
    }

    private PgbenchRun toRun(Instant startedAt, ProcessOutcome outcome, String commandLine) {
        PgbenchMeasurements measurements = parser.parse(outcome.output());
        String error = parser.parseErrors(outcome.output());
        PgbenchStatus status = status(outcome);
        String message = error;
        if (status != PgbenchStatus.SUCCESS && message == null) {
            message = "pgbench exited with code " + outcome.exitCode();
        }
        return PgbenchRun.of(
                startedAt, Instant.now(), outcome.exitCode(), status, message, commandLine, measurements);
    }

    private PgbenchStatus status(ProcessOutcome outcome) {
        if (outcome.timedOut()) {
            return PgbenchStatus.TIMEOUT;
        }
        if (outcome.succeeded()) {
            return PgbenchStatus.SUCCESS;
        }
        return PgbenchStatus.FAILED;
    }

    private PgbenchRun publish(PgbenchRun run) {
        resultStore.record(run);
        metrics.recordRun(run);
        if (run.isSuccessful()) {
            log.info(
                    "pgbench run {} finished: tps={} latency_avg={}ms transactions={}",
                    run.status(),
                    run.measurements() == null ? null : run.measurements().tps(),
                    run.measurements() == null ? null : run.measurements().latencyAverageMs(),
                    run.measurements() == null ? null : run.measurements().transactionsProcessed());
        } else {
            log.warn("pgbench run failed: status={} error={}", run.status(), run.error());
        }
        return run;
    }

    private void runQuietly() {
        try {
            run();
        } catch (RuntimeException e) {
            log.error("pgbench run failed unexpectedly", e);
        }
    }

    public Optional<PgbenchRun> latestRun() {
        return resultStore.latest();
    }

    public List<PgbenchRun> recentRuns(int limit) {
        return resultStore.recent(limit);
    }
}
