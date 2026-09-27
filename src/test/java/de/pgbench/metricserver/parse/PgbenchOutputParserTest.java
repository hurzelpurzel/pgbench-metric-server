package de.pgbench.metricserver.parse;

import static org.assertj.core.api.Assertions.assertThat;

import de.pgbench.metricserver.model.PgbenchMeasurements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgbenchOutputParserTest {

    private final PgbenchOutputParser parser = new PgbenchOutputParser();

    @Test
    @DisplayName("parses the result block of a -t run")
    void parsesTransactionRun() {
        String output = """
                pgbench (17.2, server 16.4)
                starting vacuum...end.
                transaction type: <builtin: TPC-B (sort of)>
                scaling factor: 10
                query mode: simple
                number of clients: 10
                number of threads: 1
                maximum number of tries: 1
                number of transactions per client: 1000
                number of transactions actually processed: 10000/10000
                number of failed transactions: 0 (0.000%)
                latency average = 11.013 ms
                latency stddev = 7.351 ms
                initial connection time = 45.758 ms
                tps = 896.967014 (without initial connection time)
                """;

        PgbenchMeasurements measurements = parser.parse(output);

        assertThat(measurements.clientVersion()).isEqualTo("17.2");
        assertThat(measurements.serverVersion()).isEqualTo("16.4");
        assertThat(measurements.transactionType()).isEqualTo("<builtin: TPC-B (sort of)>");
        assertThat(measurements.scalingFactor()).isEqualTo(10);
        assertThat(measurements.queryMode()).isEqualTo("simple");
        assertThat(measurements.clients()).isEqualTo(10);
        assertThat(measurements.threads()).isEqualTo(1);
        assertThat(measurements.transactionsProcessed()).isEqualTo(10000L);
        assertThat(measurements.transactionsPlanned()).isEqualTo(10000L);
        assertThat(measurements.failedTransactions()).isZero();
        assertThat(measurements.failedTransactionsPercent()).isEqualTo(0.0);
        assertThat(measurements.latencyAverageMs()).isEqualTo(11.013);
        assertThat(measurements.latencyStddevMs()).isEqualTo(7.351);
        assertThat(measurements.connectionTimeMs()).isEqualTo(45.758);
        assertThat(measurements.tps()).isEqualTo(896.967014);
        assertThat(measurements.reportedDurationSeconds()).isNull();
    }

    @Test
    @DisplayName("parses the result block of a -T run including progress reports on stderr")
    void parsesTimeLimitedRunWithProgress() {
        String output = """
                pgbench (17.2)
                progress: 1.0 s, 540.2 tps, lat 1.850 ms stddev 0.412, 0 failed
                progress: 2.0 s, 551.9 tps, lat 1.812 ms stddev 0.398, 1 failed
                transaction type: <builtin: TPC-B (sort of)>
                scaling factor: 1
                query mode: simple
                number of clients: 4
                number of threads: 1
                duration: 2 s
                number of transactions actually processed: 1100
                number of failed transactions: 1 (0.091%)
                latency average = 0.735 ms
                latency stddev = 0.412 ms
                initial connection time = 12.345 ms
                tps = 549.500000 (without initial connection time)
                """;

        PgbenchMeasurements measurements = parser.parse(output);

        assertThat(measurements.clientVersion()).isEqualTo("17.2");
        assertThat(measurements.serverVersion()).isNull();
        assertThat(measurements.reportedDurationSeconds()).isEqualTo(2);
        assertThat(measurements.transactionsProcessed()).isEqualTo(1100L);
        assertThat(measurements.transactionsPlanned()).isNull();
        assertThat(measurements.failedTransactions()).isEqualTo(1L);
        assertThat(measurements.failedTransactionsPercent()).isEqualTo(0.091);
        assertThat(measurements.tps()).isEqualTo(549.5);
    }

    @Test
    @DisplayName("keeps the values of the last result block")
    void prefersTheLastResultBlock() {
        String output = """
                number of transactions actually processed: 100
                tps = 100.0 (without initial connection time)
                number of transactions actually processed: 250
                tps = 250.0 (without initial connection time)
                """;

        PgbenchMeasurements measurements = parser.parse(output);

        assertThat(measurements.transactionsProcessed()).isEqualTo(250L);
        assertThat(measurements.tps()).isEqualTo(250.0);
    }

    @Test
    @DisplayName("understands the -C variant and retries")
    void parsesConnectVariant() {
        String output = """
                number of clients: 2
                number of transactions actually processed: 400
                number of failed transactions: 20 (5.000%)
                number of transactions retried: 20 (5.000%)
                total number of retries: 25
                latency average = 2.000 ms (including failures)
                latency stddev = 1.000 ms
                average connection time = 3.500 ms
                tps = 190.000000 (including reconnection times)
                """;

        PgbenchMeasurements measurements = parser.parse(output);

        assertThat(measurements.failedTransactions()).isEqualTo(20L);
        assertThat(measurements.latencyAverageMs()).isEqualTo(2.0);
        assertThat(measurements.connectionTimeMs()).isEqualTo(3.5);
        assertThat(measurements.tps()).isEqualTo(190.0);
    }

    @Test
    @DisplayName("returns empty measurements for output without a result block")
    void handlesOutputWithoutResults() {
        PgbenchMeasurements measurements = parser.parse("pgbench: error: could not connect to server\n");

        assertThat(measurements.isEmpty()).isTrue();
        assertThat(measurements.tps()).isNull();
    }

    @Test
    @DisplayName("treats empty and null output as empty measurements")
    void handlesEmptyOutput() {
        assertThat(parser.parse(null).isEmpty()).isTrue();
        assertThat(parser.parse("   \n  \n").isEmpty()).isTrue();
        assertThat(parser.parseErrors(null)).isNull();
        assertThat(parser.parseErrors("  ")).isNull();
    }

    @Test
    @DisplayName("collects error messages and ignores progress and the banner")
    void collectsErrors() {
        String output = """
                pgbench (17.2, server 17.2)
                pgbench: error: connection to server at "localhost" (127.0.0.1), port 5432 failed: Connection refused
                progress: 1.0 s, 1.0 tps, lat 1.000 ms stddev 0.100, 0 failed
                """;

        assertThat(parser.parseErrors(output))
                .isEqualTo("connection to server at \"localhost\" (127.0.0.1), port 5432 failed: Connection refused");
    }

    @Test
    @DisplayName("joins several errors and truncates long messages")
    void collectsSeveralErrors() {
        String output = """
                pgbench: error: first problem
                pgbench: hint: some hint
                pgbench: error: second problem
                """;

        assertThat(parser.parseErrors(output)).isEqualTo("first problem; some hint; second problem");

        String longMessage = "x".repeat(2000);
        assertThat(parser.parseErrors("pgbench: error: " + longMessage)).hasSize(1003).endsWith("...");
    }

    @Test
    @DisplayName("does not treat a missing table as a successful run")
    void reportsMissingTablesAsError() {
        String output = """
                pgbench: error: relation "pgbench_accounts" does not exist at character 135
                """;

        assertThat(parser.parseErrors(output)).contains("pgbench_accounts");
        assertThat(parser.parse(output).isEmpty()).isTrue();
    }
}
