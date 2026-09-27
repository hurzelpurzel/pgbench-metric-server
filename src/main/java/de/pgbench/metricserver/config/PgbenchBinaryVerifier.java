package de.pgbench.metricserver.config;

import java.io.File;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Warns at startup when the configured pgbench binary cannot be executed. */
@Component
@ConditionalOnProperty(prefix = "pgbench", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PgbenchBinaryVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PgbenchBinaryVerifier.class);

    private final PgbenchProperties properties;

    public PgbenchBinaryVerifier(PgbenchProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isVerifyBinary()) {
            return;
        }
        String binary = properties.getWorkload().getBinary();
        File file = new File(binary);
        if (file.isAbsolute() && !file.canExecute()) {
            log.error("pgbench binary {} does not exist or is not executable, every run will fail", binary);
            return;
        }
        try {
            Process process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor();
            if (process.exitValue() == 0) {
                log.info("pgbench binary {} found: {}", binary, output.lines().findFirst().orElse(output));
                return;
            }
            log.warn("pgbench binary {} returned exit code {} for --version", binary, process.exitValue());
        } catch (Exception e) {
            log.warn(
                    "pgbench binary {} is not available ({}) - is PostgreSQL client installed?",
                    binary,
                    e.getMessage());
        }
    }
}
