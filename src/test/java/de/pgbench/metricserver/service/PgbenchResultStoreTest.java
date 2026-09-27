package de.pgbench.metricserver.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.pgbench.metricserver.config.PgbenchProperties;
import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.model.PgbenchStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgbenchResultStoreTest {

    @Test
    @DisplayName("is empty before the first run")
    void isEmptyInitially() {
        PgbenchResultStore store = new PgbenchResultStore(new PgbenchProperties());

        assertThat(store.latest()).isEmpty();
        assertThat(store.recent(10)).isEmpty();
    }

    @Test
    @DisplayName("keeps the newest runs first and honours the configured size")
    void keepsNewestRunsFirst() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getHistory().setMaxEntries(2);
        PgbenchResultStore store = new PgbenchResultStore(properties);

        store.record(run("first"));
        store.record(run("second"));
        store.record(run("third"));

        assertThat(store.latest()).map(PgbenchRun::command).contains("third");
        assertThat(store.recent(10)).extracting(PgbenchRun::command).containsExactly("third", "second");
    }

    @Test
    @DisplayName("limits the requested history to the configured size")
    void limitsRequestedHistory() {
        PgbenchProperties properties = new PgbenchProperties();
        properties.getHistory().setMaxEntries(3);
        PgbenchResultStore store = new PgbenchResultStore(properties);
        for (int i = 1; i <= 5; i++) {
            store.record(run("run-" + i));
        }

        assertThat(store.recent(2)).extracting(PgbenchRun::command).containsExactly("run-5", "run-4");
        assertThat(store.recent(99)).hasSize(3);
        assertThat(store.recent(0)).hasSize(1);
    }

    private PgbenchRun run(String command) {
        return PgbenchRun.of(
                Instant.now(), Instant.now(), 0, PgbenchStatus.SUCCESS, null, command, null);
    }

    @Test
    @DisplayName("exposes an immutable history")
    void exposesImmutableHistory() {
        PgbenchResultStore store = new PgbenchResultStore(new PgbenchProperties());
        store.record(run("only"));

        List<PgbenchRun> runs = store.recent(5);

        assertThat(runs).isUnmodifiable();
    }
}
