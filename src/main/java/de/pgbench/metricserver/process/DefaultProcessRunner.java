package de.pgbench.metricserver.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** Runs the pgbench process and captures its combined output. */
@Component
public class DefaultProcessRunner implements ProcessRunner {

    private static final Duration DESTROY_GRACE = Duration.ofSeconds(5);
    private static final Duration READ_GRACE = Duration.ofSeconds(5);
    private static final int MAX_OUTPUT_LENGTH = 128 * 1024;

    @Override
    public ProcessOutcome run(List<String> command, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.environment().putAll(environment);
        processBuilder.redirectErrorStream(true);

        Instant startedAt = Instant.now();
        Process process = processBuilder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> read(process.getInputStream()));

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return new ProcessOutcome(
                        -1, awaitOutput(output), Duration.between(startedAt, Instant.now()), true);
            }
            return new ProcessOutcome(
                    process.exitValue(),
                    awaitOutput(output),
                    Duration.between(startedAt, Instant.now()),
                    false);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
    }

    private void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            process.waitFor(DESTROY_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private String awaitOutput(CompletableFuture<String> output) {
        try {
            return output.get(READ_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            output.cancel(true);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String read(InputStream stream) {
        StringBuilder tail = new StringBuilder();
        char[] buffer = new char[4096];
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (tail.length() + read > MAX_OUTPUT_LENGTH) {
                    tail.delete(0, tail.length() - MAX_OUTPUT_LENGTH);
                }
                tail.append(buffer, 0, read);
            }
        } catch (IOException e) {
            return tail.toString();
        }
        return tail.toString();
    }
}
