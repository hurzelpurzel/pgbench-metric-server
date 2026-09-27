package de.pgbench.metricserver.service;

import de.pgbench.metricserver.config.PgbenchProperties;
import de.pgbench.metricserver.model.PgbenchRun;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Keeps the most recent pgbench runs in memory. */
@Component
public class PgbenchResultStore {

    private final AtomicReference<PgbenchRun> latest = new AtomicReference<>();
    private final Deque<PgbenchRun> history = new ArrayDeque<>();
    private final int maxEntries;

    public PgbenchResultStore(PgbenchProperties properties) {
        this.maxEntries = properties.getHistory().getMaxEntries();
    }

    public void record(PgbenchRun run) {
        latest.set(run);
        synchronized (history) {
            history.addFirst(run);
            while (history.size() > maxEntries) {
                history.removeLast();
            }
        }
    }

    public Optional<PgbenchRun> latest() {
        return Optional.ofNullable(latest.get());
    }

    /** Returns the most recent runs, newest first. */
    public List<PgbenchRun> recent(int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, maxEntries));
        synchronized (history) {
            List<PgbenchRun> runs = new ArrayList<>(effectiveLimit);
            for (PgbenchRun run : history) {
                if (runs.size() == effectiveLimit) {
                    break;
                }
                runs.add(run);
            }
            return List.copyOf(runs);
        }
    }
}
