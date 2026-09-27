package de.pgbench.metricserver.process;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface ProcessRunner {

    /**
     * Runs the command and collects its output.
     *
     * @param command     the command and its arguments
     * @param environment additional environment variables
     * @param timeout     maximum time the process may run
     * @throws IOException          when the process cannot be started
     * @throws InterruptedException when the calling thread is interrupted
     */
    ProcessOutcome run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException;
}
