package de.pgbench.metricserver.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.pgbench.metricserver.config.PgbenchProperties.WorkloadMode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgbenchCommandBuilderTest {

    private final PgbenchCommandBuilder builder = new PgbenchCommandBuilder();

    @Test
    @DisplayName("builds a command for the default built-in workload")
    void buildsDefaultCommand() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getConnection().setPassword("secret");

        List<String> command = builder.buildCommand(properties);

        assertThat(command)
                .containsExactly(
                        "pgbench",
                        "-h", "localhost",
                        "-p", "5432",
                        "-U", "postgres",
                        "-d", "postgres",
                        "-c", "4",
                        "-j", "1",
                        "-T", "10");
        assertThat(builder.describe(command)).doesNotContain("secret");
    }

    @Test
    @DisplayName("uses the run duration and no progress by default")
    void usesDurationInSeconds() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getWorkload().setDuration(Duration.ofSeconds(45));

        assertThat(builder.buildCommand(properties)).containsSubsequence("-T", "45").doesNotContain("-P");
    }

    @Test
    @DisplayName("prefers the transaction count over the duration")
    void prefersTransactionsOverDuration() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getWorkload().setTransactions(5000);

        assertThat(builder.buildCommand(properties)).containsSubsequence("-t", "5000").doesNotContain("-T");
    }

    @Test
    @DisplayName("builds a command for a custom script")
    void buildsScriptCommand() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getWorkload().setMode(WorkloadMode.SCRIPT);
        properties.getWorkload().setScriptPath("/etc/pgbench/read-only.sql");
        properties.getWorkload().setVacuum(false);
        properties.getWorkload().getVariables().putAll(Map.of("table", "orders", "tenant", "42"));
        properties.getWorkload().setProgressInterval(Duration.ofSeconds(15));
        properties.getWorkload().setQueryMode("prepared");
        properties.getWorkload().setRate(200);
        properties.getWorkload().setMaxTries(3);
        properties.getWorkload().setReportPerCommand(true);
        properties.getWorkload().setScaleFactor(25);
        properties.getWorkload().getExtraArguments().add("--latency-limit=500");

        List<String> command = builder.buildCommand(properties);

        assertThat(command)
                .containsSubsequence("-f", "/etc/pgbench/read-only.sql", "-T", "10", "-s", "25", "-n", "-P", "15",
                        "-M", "prepared", "-R", "200", "--max-tries", "3", "-r", "--latency-limit=500")
                .containsSubsequence("-D", "table=orders")
                .containsSubsequence("-D", "tenant=42")
                .doesNotContain("-b");
        assertThat(command).endsWith("--latency-limit=500");
    }

    @Test
    @DisplayName("passes a built-in script name")
    void buildsBuiltinCommand() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getWorkload().setBuiltinScript("select-only");

        assertThat(builder.buildCommand(properties)).containsSubsequence("-b", "select-only");
    }

    @Test
    @DisplayName("omits empty connection options")
    void omitsEmptyOptions() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getConnection().setSslmode("");
        properties.getWorkload().setBuiltinScript("");

        assertThat(builder.buildCommand(properties)).doesNotContain("-b");
        assertThat(builder.buildEnvironment(properties)).doesNotContainKey("PGSSLMODE");
    }

    @Test
    @DisplayName("passes credentials and connection options as environment variables")
    void buildsEnvironment() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getConnection().setPassword("secret");
        properties.getConnection().setSslmode("require");
        properties.getConnection().setConnectTimeout(Duration.ofSeconds(7));
        properties.getConnection().getOptions().put("replication", "true");

        Map<String, String> environment = builder.buildEnvironment(properties);

        assertThat(environment)
                .containsEntry("PGPASSWORD", "secret")
                .containsEntry("PGSSLMODE", "require")
                .containsEntry("PGCONNECT_TIMEOUT", "7")
                .containsEntry("PGREPLICATION", "true")
                .containsEntry("PGAPPNAME", "pgbench-metric-server");
    }
}
