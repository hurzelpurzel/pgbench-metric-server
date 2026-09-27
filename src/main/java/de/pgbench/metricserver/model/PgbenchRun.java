package de.pgbench.metricserver.model;

import java.time.Duration;
import java.time.Instant;

/**
 * One executed pgbench run including the parsed results and the process metadata.
 *
 * @param startedAt           when the process was started
 * @param finishedAt          when the process terminated
 * @param wallClock           time the process was running
 * @param exitCode            exit code of pgbench, -1 when the process never started
 * @param status              outcome of the run
 * @param error               error message reported by pgbench, may be null
 * @param command             the executed command line, never contains credentials
 * @param measurements        the parsed pgbench result, may be null when nothing could be parsed
 */
public record PgbenchRun(
        Instant startedAt,
        Instant finishedAt,
        Duration wallClock,
        int exitCode,
        PgbenchStatus status,
        String error,
        String command,
        PgbenchMeasurements measurements) {

    public boolean isSuccessful() {
        return status == PgbenchStatus.SUCCESS;
    }

    public static PgbenchRun of(
            Instant startedAt,
            Instant finishedAt,
            int exitCode,
            PgbenchStatus status,
            String error,
            String command,
            PgbenchMeasurements measurements) {
        return new PgbenchRun(
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt),
                exitCode,
                status,
                error,
                command,
                measurements);
    }

    public static PgbenchRun error(Instant startedAt, String message, String command) {
        return of(
                startedAt,
                Instant.now(),
                -1,
                PgbenchStatus.ERROR,
                message,
                command,
                null);
    }
}
