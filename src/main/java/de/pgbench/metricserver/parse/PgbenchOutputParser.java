package de.pgbench.metricserver.parse;

import de.pgbench.metricserver.model.PgbenchMeasurements;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses the result block that pgbench writes to stdout. Lines that are not part of the result
 * block, for example the version banner, the vacuum progress on stderr or the per-interval
 * progress reports, are ignored. If a value appears more than once the last occurrence wins,
 * which is the one from the final result block.
 */
@Component
public class PgbenchOutputParser {

    private static final int MAX_ERROR_LENGTH = 1000;

    private static final Pattern BANNER =
            Pattern.compile("^pgbench \\((?<client>[^,)]+?)(?:, server (?<server>[^,)]+?))?\\)$");
    private static final Pattern TRANSACTION_TYPE = Pattern.compile("^transaction type: (?<value>.+)$");
    private static final Pattern SCALING_FACTOR = Pattern.compile("^scaling factor: (?<value>\\d+)$");
    private static final Pattern QUERY_MODE = Pattern.compile("^query mode: (?<value>.+)$");
    private static final Pattern CLIENTS = Pattern.compile("^number of clients: (?<value>\\d+)$");
    private static final Pattern THREADS = Pattern.compile("^number of threads: (?<value>\\d+)$");
    private static final Pattern DURATION = Pattern.compile("^duration: (?<value>\\d+) s$");
    private static final Pattern TRANSACTIONS_PROCESSED = Pattern.compile(
            "^number of transactions actually processed: (?<value>\\d+)(?:/(?<planned>\\d+))?$");
    private static final Pattern FAILED_TRANSACTIONS =
            Pattern.compile("^number of failed transactions: (?<value>\\d+) \\((?<percent>[\\d.]+)%\\)$");
    private static final Pattern LATENCY_AVERAGE =
            Pattern.compile("^latency average = (?<value>[\\d.]+) ms(?: .*)?$");
    private static final Pattern LATENCY_STDDEV = Pattern.compile("^latency stddev = (?<value>[\\d.]+) ms$");
    private static final Pattern CONNECTION_TIME = Pattern.compile(
            "^(?:initial connection time|average connection time) = (?<value>[\\d.]+) ms$");
    private static final Pattern TPS = Pattern.compile("^tps = (?<value>[\\d.]+) \\(.+\\)$");
    private static final Pattern ERROR_LINE =
            Pattern.compile("^(?:pgbench: )?(?:error|fatal|critical|warning|hint): (?<value>.+)$");

    /** Parses the result block, an output without any known line yields empty measurements. */
    public PgbenchMeasurements parse(String output) {
        PgbenchMeasurements.Builder builder = PgbenchMeasurements.builder();
        if (output == null || output.isBlank()) {
            return builder.build();
        }
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            apply(builder, line);
        }
        return builder.build();
    }

    private void apply(PgbenchMeasurements.Builder builder, String line) {
        Matcher banner = BANNER.matcher(line);
        if (banner.matches()) {
            builder.clientVersion(banner.group("client")).serverVersion(banner.group("server"));
            return;
        }
        String value = group(TRANSACTION_TYPE, line, "value");
        if (value != null) {
            builder.transactionType(value);
            return;
        }
        Integer scalingFactor = integer(SCALING_FACTOR, line);
        if (scalingFactor != null) {
            builder.scalingFactor(scalingFactor);
            return;
        }
        value = group(QUERY_MODE, line, "value");
        if (value != null) {
            builder.queryMode(value);
            return;
        }
        Integer clients = integer(CLIENTS, line);
        if (clients != null) {
            builder.clients(clients);
            return;
        }
        Integer threads = integer(THREADS, line);
        if (threads != null) {
            builder.threads(threads);
            return;
        }
        Integer duration = integer(DURATION, line);
        if (duration != null) {
            builder.reportedDurationSeconds(duration);
            return;
        }
        Matcher transactions = TRANSACTIONS_PROCESSED.matcher(line);
        if (transactions.matches()) {
            builder.transactionsProcessed(asLong(transactions.group("value")));
            if (transactions.group("planned") != null) {
                builder.transactionsPlanned(asLong(transactions.group("planned")));
            }
            return;
        }
        Matcher failed = FAILED_TRANSACTIONS.matcher(line);
        if (failed.matches()) {
            builder.failedTransactions(asLong(failed.group("value")))
                    .failedTransactionsPercent(asDouble(failed.group("percent")));
            return;
        }
        Double latencyAverage = decimal(LATENCY_AVERAGE, line);
        if (latencyAverage != null) {
            builder.latencyAverageMs(latencyAverage);
            return;
        }
        Double latencyStddev = decimal(LATENCY_STDDEV, line);
        if (latencyStddev != null) {
            builder.latencyStddevMs(latencyStddev);
            return;
        }
        Double connectionTime = decimal(CONNECTION_TIME, line);
        if (connectionTime != null) {
            builder.connectionTimeMs(connectionTime);
            return;
        }
        Double tps = decimal(TPS, line);
        if (tps != null) {
            builder.tps(tps);
        }
    }

    /**
     * Collects the diagnostics pgbench printed. Progress reports and the connection banner are
     * not treated as errors.
     */
    public String parseErrors(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        List<String> errors = new ArrayList<>();
        for (String rawLine : output.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("progress:") || line.startsWith("pgbench (")) {
                continue;
            }
            Matcher matcher = ERROR_LINE.matcher(line);
            if (matcher.matches() && !errors.contains(matcher.group("value"))) {
                errors.add(matcher.group("value"));
            }
        }
        if (errors.isEmpty()) {
            return null;
        }
        String message = String.join("; ", errors);
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) + "..." : message;
    }

    private String group(Pattern pattern, String line, String group) {
        Matcher matcher = pattern.matcher(line);
        return matcher.matches() ? matcher.group(group) : null;
    }

    private Integer integer(Pattern pattern, String line) {
        String value = group(pattern, line, "value");
        return value == null ? null : Integer.valueOf(value);
    }

    private Double decimal(Pattern pattern, String line) {
        String value = group(pattern, line, "value");
        return value == null ? null : asDouble(value);
    }

    private static Long asLong(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private static Double asDouble(String value) {
        return value == null ? null : Double.valueOf(value);
    }
}
