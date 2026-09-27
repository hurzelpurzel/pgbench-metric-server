package de.pgbench.metricserver.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.pgbench.metricserver.model.PgbenchRun;
import de.pgbench.metricserver.model.PgbenchStatus;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Boots the whole application with a stub binary instead of a real pgbench, so the scheduled run,
 * the REST API and the Prometheus endpoint are verified end to end without a database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PgbenchMetricServerIntegrationTest {

    private static final String STUB_OUTPUT = """
            pgbench (17.2, server 17.2)
            starting vacuum...end.
            transaction type: <builtin: TPC-B (sort of)>
            scaling factor: 1
            query mode: simple
            number of clients: 4
            number of threads: 1
            maximum number of tries: 1
            duration: 10 s
            number of transactions actually processed: 40000
            number of failed transactions: 0 (0.000%)
            latency average = 1.000 ms
            latency stddev = 0.500 ms
            initial connection time = 5.000 ms
            tps = 4000.000000 (without initial connection time)
            """;

    private static Path stubBinary;

    @LocalServerPort
    private int port;

    private HttpClient httpClient;
    private RestClient restClient;

    @BeforeAll
    static void createStubBinary() throws IOException {
        stubBinary = Files.createTempFile("pgbench-stub", ".sh");
        String stub = "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 'pgbench (17.2)'; exit 0; fi\n"
                + "cat <<'EOF'\n"
                + STUB_OUTPUT
                + "EOF\n";
        Files.writeString(stubBinary, stub);
        if (!stubBinary.toFile().setExecutable(true)) {
            throw new IllegalStateException("the pgbench stub is not executable");
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pgbench.workload.binary", () -> stubBinary.toString());
        registry.add("pgbench.interval", () -> "2s");
        registry.add("pgbench.initial-delay", () -> "100ms");
        registry.add("pgbench.verify-binary", () -> "false");
        registry.add("pgbench.connection.password", () -> "secret");
        registry.add("logging.level.de.pgbench.metricserver", () -> "DEBUG");
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpClient() {
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        restClient = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    @DisplayName("the scheduled run exposes the results as Prometheus metrics")
    void exposesPrometheusMetrics() throws Exception {
        awaitFirstRun();

        String scrape = get("/actuator/prometheus").body();

        assertThat(scrape)
                .contains("pgbench_tps 4000.0")
                .contains("pgbench_latency_average")
                .contains("pgbench_transactions_processed 40000.0")
                .contains("pgbench_transactions_failed 0.0")
                .contains("pgbench_clients 4.0")
                .contains("pgbench_runs_total")
                .contains("pgbench_last_run_successful 1.0")
                .contains("version=\"17.2\"");
    }

    @Test
    @DisplayName("the latest result is available as JSON")
    void exposesLatestResult() throws Exception {
        awaitFirstRun();

        String body = get("/api/pgbench/latest").body();
        PgbenchRun run = restClient.get().uri("/api/pgbench/latest").retrieve().body(PgbenchRun.class);

        assertThat(body).doesNotContain("\"empty\"");
        assertThat(run).isNotNull();
        assertThat(run.status()).isEqualTo(PgbenchStatus.SUCCESS);
        assertThat(run.exitCode()).isZero();
        assertThat(run.measurements().tps()).isEqualTo(4000.0);
        assertThat(run.measurements().clientVersion()).isEqualTo("17.2");
        assertThat(run.measurements().serverVersion()).isEqualTo("17.2");
        assertThat(run.command()).contains(stubBinary.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("the run history keeps the newest runs first")
    void exposesRunHistory() throws Exception {
        awaitFirstRun();
        awaitRunCount(2);

        List<PgbenchRun> runs = runs(5);

        assertThat(runs.size()).isGreaterThanOrEqualTo(2);
        assertThat(runs.get(0).startedAt()).isAfterOrEqualTo(runs.get(1).startedAt());
    }

    @Test
    @DisplayName("a run can be triggered through the API")
    void triggersRunThroughApi() throws Exception {
        awaitFirstRun();
        int before = countRuns();

        HttpResponse<String> response = post("/api/pgbench/run");
        awaitRunCount(before + 1);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("accepted");
    }

    @Test
    @DisplayName("the health endpoint is exposed")
    void exposesHealth() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private int countRuns() {
        return runs(100).size();
    }

    private List<PgbenchRun> runs(int limit) {
        return restClient
                .get()
                .uri("/api/pgbench/runs?limit={limit}", limit)
                .retrieve()
                .body(new ParameterizedTypeReference<List<PgbenchRun>>() {});
    }

    private void awaitFirstRun() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (get("/api/pgbench/latest").statusCode() == 200) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the scheduled pgbench run did not produce a result");
    }

    private void awaitRunCount(int expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (countRuns() >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("expected at least " + expected + " runs");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
