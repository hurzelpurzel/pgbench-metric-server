package de.pgbench.metricserver.web;

import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.service.PgbenchService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pgbench")
public class PgbenchController {

    private final PgbenchService pgbenchService;

    public PgbenchController(PgbenchService pgbenchService) {
        this.pgbenchService = pgbenchService;
    }

    /** The most recent run, 404 as long as no run has finished. */
    @GetMapping("/latest")
    public ResponseEntity<PgbenchRun> latest() {
        return pgbenchService.latestRun()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The most recent runs, newest first. */
    @GetMapping("/runs")
    public List<PgbenchRun> runs(@RequestParam(defaultValue = "10") int limit) {
        return pgbenchService.recentRuns(limit);
    }

    /** Starts a benchmark run in the background. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run() {
        if (pgbenchService.isRunning()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a pgbench run is already in progress");
        }
        if (!pgbenchService.trigger()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "the pgbench run was not accepted");
        }
        return ResponseEntity.accepted()
                .body(Map.of("status", "accepted", "latest", "/api/pgbench/latest"));
    }
}
