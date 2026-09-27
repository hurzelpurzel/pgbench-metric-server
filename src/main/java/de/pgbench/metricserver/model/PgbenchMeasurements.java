package de.pgbench.metricserver.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * The values pgbench reports in its result block. All fields are optional because pgbench omits
 * lines depending on the selected options and stops printing after the first error.
 *
 * @param clientVersion             version banner of the pgbench binary, e.g. "17.2"
 * @param serverVersion             server version reported by the pgbench banner
 * @param transactionType           e.g. "&lt;builtin: TPC-B (sort of)&gt;"
 * @param scalingFactor             value of the "scaling factor" line
 * @param queryMode                 simple, extended or prepared
 * @param clients                   number of concurrent sessions
 * @param threads                   number of pgbench worker threads
 * @param transactionsProcessed     completed transactions
 * @param transactionsPlanned       intended transactions, only reported in -t mode
 * @param failedTransactions        transactions that failed
 * @param failedTransactionsPercent share of failed transactions in percent
 * @param latencyAverageMs          average transaction latency
 * @param latencyStddevMs           standard deviation of the transaction latency
 * @param tps                       transactions per second
 * @param connectionTimeMs          initial (or average) connection time
 * @param reportedDurationSeconds   benchmark duration as reported by pgbench
 */
public record PgbenchMeasurements(
        String clientVersion,
        String serverVersion,
        String transactionType,
        Integer scalingFactor,
        String queryMode,
        Integer clients,
        Integer threads,
        Long transactionsProcessed,
        Long transactionsPlanned,
        Long failedTransactions,
        Double failedTransactionsPercent,
        Double latencyAverageMs,
        Double latencyStddevMs,
        Double tps,
        Double connectionTimeMs,
        Integer reportedDurationSeconds) {

    @JsonIgnore
    public boolean isEmpty() {
        return transactionsProcessed == null && tps == null && latencyAverageMs == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String clientVersion;
        private String serverVersion;
        private String transactionType;
        private Integer scalingFactor;
        private String queryMode;
        private Integer clients;
        private Integer threads;
        private Long transactionsProcessed;
        private Long transactionsPlanned;
        private Long failedTransactions;
        private Double failedTransactionsPercent;
        private Double latencyAverageMs;
        private Double latencyStddevMs;
        private Double tps;
        private Double connectionTimeMs;
        private Integer reportedDurationSeconds;

        public Builder clientVersion(String clientVersion) {
            this.clientVersion = clientVersion;
            return this;
        }

        public Builder serverVersion(String serverVersion) {
            this.serverVersion = serverVersion;
            return this;
        }

        public Builder transactionType(String transactionType) {
            this.transactionType = transactionType;
            return this;
        }

        public Builder scalingFactor(Integer scalingFactor) {
            this.scalingFactor = scalingFactor;
            return this;
        }

        public Builder queryMode(String queryMode) {
            this.queryMode = queryMode;
            return this;
        }

        public Builder clients(Integer clients) {
            this.clients = clients;
            return this;
        }

        public Builder threads(Integer threads) {
            this.threads = threads;
            return this;
        }

        public Builder transactionsProcessed(Long transactionsProcessed) {
            this.transactionsProcessed = transactionsProcessed;
            return this;
        }

        public Builder transactionsPlanned(Long transactionsPlanned) {
            this.transactionsPlanned = transactionsPlanned;
            return this;
        }

        public Builder failedTransactions(Long failedTransactions) {
            this.failedTransactions = failedTransactions;
            return this;
        }

        public Builder failedTransactionsPercent(Double failedTransactionsPercent) {
            this.failedTransactionsPercent = failedTransactionsPercent;
            return this;
        }

        public Builder latencyAverageMs(Double latencyAverageMs) {
            this.latencyAverageMs = latencyAverageMs;
            return this;
        }

        public Builder latencyStddevMs(Double latencyStddevMs) {
            this.latencyStddevMs = latencyStddevMs;
            return this;
        }

        public Builder tps(Double tps) {
            this.tps = tps;
            return this;
        }

        public Builder connectionTimeMs(Double connectionTimeMs) {
            this.connectionTimeMs = connectionTimeMs;
            return this;
        }

        public Builder reportedDurationSeconds(Integer reportedDurationSeconds) {
            this.reportedDurationSeconds = reportedDurationSeconds;
            return this;
        }

        public PgbenchMeasurements build() {
            return new PgbenchMeasurements(
                    clientVersion,
                    serverVersion,
                    transactionType,
                    scalingFactor,
                    queryMode,
                    clients,
                    threads,
                    transactionsProcessed,
                    transactionsPlanned,
                    failedTransactions,
                    failedTransactionsPercent,
                    latencyAverageMs,
                    latencyStddevMs,
                    tps,
                    connectionTimeMs,
                    reportedDurationSeconds);
        }
    }
}
