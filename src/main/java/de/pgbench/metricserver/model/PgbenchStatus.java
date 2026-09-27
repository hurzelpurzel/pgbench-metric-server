package de.pgbench.metricserver.model;

public enum PgbenchStatus {

    /** pgbench exited with code 0 and reported usable results. */
    SUCCESS,

    /** pgbench exited with a non-zero code, it may still have reported partial results. */
    FAILED,

    /** The pgbench process was killed because it exceeded the configured timeout. */
    TIMEOUT,

    /** The pgbench process could not be started or the result could not be read. */
    ERROR
}
