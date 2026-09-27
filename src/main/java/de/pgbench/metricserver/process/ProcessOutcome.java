package de.pgbench.metricserver.process;

import java.time.Duration;

/**
 * The result of an executed process.
 *
 * @param exitCode exit code of the process, -1 when it was killed before it reported one
 * @param output   combined stdout and stderr, truncated to the tail when it is too large
 * @param duration wall clock time the process was running
 * @param timedOut true when the process was killed because it exceeded the timeout
 */
public record ProcessOutcome(int exitCode, String output, Duration duration, boolean timedOut) {

    public boolean succeeded() {
        return exitCode == 0 && !timedOut;
    }
}
