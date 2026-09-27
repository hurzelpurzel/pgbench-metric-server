package de.pgbench.metricserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts a pgbench run after every configured interval. */
@Component
@ConditionalOnProperty(prefix = "pgbench", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PgbenchScheduler {

    private static final Logger log = LoggerFactory.getLogger(PgbenchScheduler.class);

    private final PgbenchService pgbenchService;

    public PgbenchScheduler(PgbenchService pgbenchService) {
        this.pgbenchService = pgbenchService;
    }

    @Scheduled(
            initialDelayString = "${pgbench.initial-delay:PT10S}",
            fixedDelayString = "${pgbench.interval:PT1M}")
    public void run() {
        if (!pgbenchService.trigger()) {
            log.debug("pgbench run not started, a run is still in progress");
        }
    }
}
