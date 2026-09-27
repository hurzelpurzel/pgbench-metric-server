package de.pgbench.metricserver.config;

import de.pgbench.metricserver.config.PgbenchProperties.Connection;
import de.pgbench.metricserver.config.PgbenchProperties.Workload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Translates the configuration into a pgbench command line and the environment for the process. */
@Component
public class PgbenchCommandBuilder {

    public List<String> buildCommand(PgbenchProperties properties) {
        Workload workload = properties.getWorkload();
        Connection connection = properties.getConnection();

        List<String> command = new ArrayList<>();
        command.add(workload.getBinary());
        addConnectionOptions(command, connection);
        addWorkloadOptions(command, workload);
        command.addAll(workload.getExtraArguments());
        return List.copyOf(command);
    }

    private void addConnectionOptions(List<String> command, Connection connection) {
        addOption(command, "-h", connection.getHost());
        addOption(command, "-p", Integer.toString(connection.getPort()));
        addOption(command, "-U", connection.getUsername());
        addOption(command, "-d", connection.getDatabase());
    }

    private void addWorkloadOptions(List<String> command, Workload workload) {
        addOption(command, "-c", Integer.toString(workload.getClients()));
        addOption(command, "-j", Integer.toString(workload.getJobs()));

        switch (workload.getMode()) {
            case BUILTIN -> addOption(command, "-b", workload.getBuiltinScript());
            case SCRIPT -> addOption(command, "-f", workload.getScriptPath());
        }

        if (workload.getTransactions() > 0) {
            addOption(command, "-t", Integer.toString(workload.getTransactions()));
        } else {
            addOption(command, "-T", Long.toString(Math.max(1, workload.getDuration().toSeconds())));
        }

        if (workload.getScaleFactor() > 0) {
            addOption(command, "-s", Integer.toString(workload.getScaleFactor()));
        }
        if (!workload.isVacuum()) {
            command.add("-n");
        }
        if (!workload.getProgressInterval().isZero()) {
            addOption(
                    command,
                    "-P",
                    Long.toString(Math.max(1, workload.getProgressInterval().toSeconds())));
        }
        addOption(command, "-M", workload.getQueryMode());
        if (workload.getRate() > 0) {
            addOption(command, "-R", Integer.toString(workload.getRate()));
        }
        if (workload.getMaxTries() != 1) {
            addOption(command, "--max-tries", Integer.toString(workload.getMaxTries()));
        }
        if (workload.isReportPerCommand()) {
            command.add("-r");
        }
        for (Map.Entry<String, String> variable : workload.getVariables().entrySet()) {
            addOption(command, "-D", variable.getKey() + "=" + variable.getValue());
        }
    }

    /**
     * Builds the environment for the pgbench process. Credentials are passed as environment
     * variables, they never appear in the command line and are not logged.
     */
    public Map<String, String> buildEnvironment(PgbenchProperties properties) {
        Connection connection = properties.getConnection();
        Map<String, String> environment = new LinkedHashMap<>();
        if (isSet(connection.getPassword())) {
            environment.put("PGPASSWORD", connection.getPassword());
        }
        if (isSet(connection.getSslmode())) {
            environment.put("PGSSLMODE", connection.getSslmode());
        }
        if (connection.getConnectTimeout() != null && !connection.getConnectTimeout().isZero()) {
            environment.put(
                    "PGCONNECT_TIMEOUT", Long.toString(Math.max(1, connection.getConnectTimeout().toSeconds())));
        }
        for (Map.Entry<String, String> option : connection.getOptions().entrySet()) {
            environment.put("PG" + option.getKey().toUpperCase(), option.getValue());
        }
        environment.put("PGAPPNAME", "pgbench-metric-server");
        return Map.copyOf(environment);
    }

    /** Renders a command line for logs and the REST API. */
    public String describe(List<String> command) {
        return String.join(" ", command);
    }

    private void addOption(List<String> command, String option, String value) {
        if (isSet(value)) {
            command.add(option);
            command.add(value.strip());
        }
    }

    private boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
