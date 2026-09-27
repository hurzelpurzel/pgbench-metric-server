package de.pgbench.metricserver.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "pgbench")
public class PgbenchProperties {

    /** Whether the scheduled pgbench runs are active. */
    private boolean enabled = true;

    /** Delay between two pgbench runs. */
    @NotNull
    private Duration interval = Duration.ofMinutes(1);

    /** Delay before the first run after application startup. */
    @NotNull
    private Duration initialDelay = Duration.ofSeconds(10);

    /** Log a warning at startup when the configured pgbench binary is not executable. */
    private boolean verifyBinary = true;

    /** Hard limit for a single pgbench process. */
    @NotNull
    private Duration timeout = Duration.ofSeconds(90);

    @Valid
    private final Connection connection = new Connection();

    @Valid
    private final Workload workload = new Workload();

    @Valid
    private final History history = new History();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public boolean isVerifyBinary() {
        return verifyBinary;
    }

    public void setVerifyBinary(boolean verifyBinary) {
        this.verifyBinary = verifyBinary;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public Connection getConnection() {
        return connection;
    }

    public Workload getWorkload() {
        return workload;
    }

    public History getHistory() {
        return history;
    }

    public static class Connection {

        @NotBlank
        private String host = "localhost";

        @Min(1)
        private int port = 5432;

        @NotBlank
        private String database = "postgres";

        @NotBlank
        private String username = "postgres";

        /** Passed to pgbench via the PGPASSWORD environment variable, never on the command line. */
        private String password = "";

        /** Optional PGSSLMODE value. */
        private String sslmode = "";

        /** Optional PGCONNECT_TIMEOUT value. */
        private Duration connectTimeout = Duration.ofSeconds(10);

        /** Additional libpq connection options, e.g. "application_name=pgbench-metric-server". */
        private final Map<String, String> options = new LinkedHashMap<>();

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getSslmode() {
            return sslmode;
        }

        public void setSslmode(String sslmode) {
            this.sslmode = sslmode;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Map<String, String> getOptions() {
            return options;
        }
    }

    public enum WorkloadMode {
        /** Use a built-in pgbench script, see the built-in script name. */
        BUILTIN,
        /** Use a custom SQL script file. */
        SCRIPT
    }

    public static class Workload {

        /** Path to (or name of) the pgbench executable. */
        @NotBlank
        private String binary = "pgbench";

        @NotNull
        private WorkloadMode mode = WorkloadMode.BUILTIN;

        /** Built-in script name, e.g. tpcb-like, simple-update, select-only. Empty means the pgbench default. */
        private String builtinScript = "";

        /** SQL script file used when the mode is SCRIPT. */
        private String scriptPath = "";

        /** Number of concurrent database sessions. */
        @Min(1)
        private int clients = 4;

        /** Number of pgbench worker threads. */
        @Min(1)
        private int jobs = 1;

        /** Run duration, used when transactions is zero. */
        @NotNull
        private Duration duration = Duration.ofSeconds(10);

        /** Number of transactions per client. Takes precedence over the duration when greater than zero. */
        @Min(0)
        private int transactions = 0;

        /** Scale factor reported by pgbench, see --scale. */
        @Min(0)
        private int scaleFactor = 0;

        /** Whether pgbench may vacuum the pgbench_* tables before the run. */
        private boolean vacuum = true;

        /** Progress report interval, see --progress. Zero disables progress output. */
        @NotNull
        private Duration progressInterval = Duration.ZERO;

        /** Query protocol, see --protocol: simple, extended or prepared. */
        private String queryMode = "";

        /** Transactions per second to target, see --rate. Zero runs as fast as possible. */
        @Min(0)
        private int rate = 0;

        /** Maximum number of tries for transactions with serialization or deadlock errors. */
        @Min(1)
        private int maxTries = 1;

        /** Per-command statistics, see --report-per-command. */
        private boolean reportPerCommand = false;

        /** Script variables, passed as -D name=value. */
        private final Map<String, String> variables = new LinkedHashMap<>();

        /** Raw pgbench arguments appended to the generated command line. */
        private final List<String> extraArguments = new ArrayList<>();

        public String getBinary() {
            return binary;
        }

        public void setBinary(String binary) {
            this.binary = binary;
        }

        public WorkloadMode getMode() {
            return mode;
        }

        public void setMode(WorkloadMode mode) {
            this.mode = mode;
        }

        public String getBuiltinScript() {
            return builtinScript;
        }

        public void setBuiltinScript(String builtinScript) {
            this.builtinScript = builtinScript;
        }

        public String getScriptPath() {
            return scriptPath;
        }

        public void setScriptPath(String scriptPath) {
            this.scriptPath = scriptPath;
        }

        public int getClients() {
            return clients;
        }

        public void setClients(int clients) {
            this.clients = clients;
        }

        public int getJobs() {
            return jobs;
        }

        public void setJobs(int jobs) {
            this.jobs = jobs;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }

        public int getTransactions() {
            return transactions;
        }

        public void setTransactions(int transactions) {
            this.transactions = transactions;
        }

        public int getScaleFactor() {
            return scaleFactor;
        }

        public void setScaleFactor(int scaleFactor) {
            this.scaleFactor = scaleFactor;
        }

        public boolean isVacuum() {
            return vacuum;
        }

        public void setVacuum(boolean vacuum) {
            this.vacuum = vacuum;
        }

        public Duration getProgressInterval() {
            return progressInterval;
        }

        public void setProgressInterval(Duration progressInterval) {
            this.progressInterval = progressInterval;
        }

        public String getQueryMode() {
            return queryMode;
        }

        public void setQueryMode(String queryMode) {
            this.queryMode = queryMode;
        }

        public int getRate() {
            return rate;
        }

        public void setRate(int rate) {
            this.rate = rate;
        }

        public int getMaxTries() {
            return maxTries;
        }

        public void setMaxTries(int maxTries) {
            this.maxTries = maxTries;
        }

        public boolean isReportPerCommand() {
            return reportPerCommand;
        }

        public void setReportPerCommand(boolean reportPerCommand) {
            this.reportPerCommand = reportPerCommand;
        }

        public Map<String, String> getVariables() {
            return variables;
        }

        public List<String> getExtraArguments() {
            return extraArguments;
        }
    }

    public static class History {

        /** Number of completed runs kept in memory and offered by the REST API. */
        @Min(1)
        private int maxEntries = 60;

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }
}
