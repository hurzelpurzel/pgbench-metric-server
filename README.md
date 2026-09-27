# pgbench-metric-server

A small Spring Boot service that runs the PostgreSQL benchmark tool
[pgbench](https://www.postgresql.org/docs/current/pgbench.html) on a schedule - by default once
per minute - and exposes the result as Prometheus metrics and as JSON.

- runs `pgbench` as a separate process, the benchmark never runs inside the JVM
- every measurement of the last run is a Prometheus gauge
- results, including failed runs and the error message, are also available over REST
- no database driver and no JDBC connection are needed, the service only calls the CLI tool

## Requirements

- JDK 25 (the build is configured with `java.version=25`, the Spring Boot baseline is 17)
- `pgbench` on the `PATH` of the service user, or configured with `pgbench.workload.binary`
- for the container image: `podman` or `docker`, no local JDK or `pgbench` is needed

## Build and run

```bash
./mvnw package
java -jar target/pgbench-metric-server-0.1.0-SNAPSHOT.jar
```

The built-in TPC-B like scenario needs the pgbench tables, so initialize them once per database:

```bash
pgbench -i -h localhost -U postgres -s 10 mydb
```

A custom SQL script does not need `pgbench -i`, see [Workload modes](#workload-modes).

## Container image

The `Dockerfile` is based on [UBI 10](https://catalog.redhat.com/software/containers/ubi10/10) and
meets the usual OpenShift requirements: it runs as a non-root user, tolerates the arbitrary UID the
platform assigns and exposes no privileged port.

```bash
podman build -t pgbench-metric-server:0.1.0-SNAPSHOT .
```

The image bundles the JDK 25 and `pgbench`, nothing has to be installed on top. `pgbench` is not part
of the UBI 10 repositories, so the image build takes the client tools from the PostgreSQL upstream
[PGDG](https://www.postgresql.org/download/linux/) repository.

Prebuilt images are published in the GitHub container registry:

```bash
podman pull ghcr.io/hurzelpurzel/pgbench-metric-server:0.1.0-SNAPSHOT
```

Tags: `0.1.0-SNAPSHOT` for the current version, `latest` and `sha-<commit>` for the current build.
Configuration works exactly as outside a container, the password belongs into an environment
variable:

```bash
podman run --rm -p 8080:8080 \
  -e PGBENCH_PASSWORD=secret \
  -e PGBENCH_CONNECTION_HOST=postgres \
  -e PGBENCH_INTERVAL=30s \
  ghcr.io/hurzelpurzel/pgbench-metric-server:0.1.0-SNAPSHOT
```

JVM flags are set with `JAVA_TOOL_OPTIONS`, the heap defaults to 75% of the container memory limit
and a memory-exhausted JVM exits instead of being killed. `JAVA_OPTS` has no effect, the Spring Boot
launcher ignores it.

### OpenShift

The image carries the labels that make `oc new-app --image=...` create a Route for port 8080, and
the Kubernetes probes are served by the actuator:

| Probe | Path |
| --- | --- |
| liveness | `/actuator/health/liveness` |
| readiness | `/actuator/health/readiness` |

```bash
oc create secret generic pgbench --from-literal=PGBENCH_PASSWORD=secret
oc new-app ghcr.io/hurzelpurzel/pgbench-metric-server:0.1.0-SNAPSHOT \
  --name pgbench-metric-server \
  --from-secret=pgbench \
  --env PGBENCH_CONNECTION_HOST=postgres
```

A running benchmark is awaited during the shutdown, which takes up to
`spring.lifecycle.timeout-per-shutdown-phase` (2m). Raise the grace period of the pod, otherwise
`SIGTERM` leads to a forced kill before the current run has finished:

```bash
oc patch dc/pgbench-metric-server \
  -p '{"spec":{"template":{"spec":{"terminationGracePeriodSeconds":150}}}}'
```

A private package needs an image pull secret in the service accounts of the namespace:

```bash
oc create secret docker-registry ghcr --docker-server=ghcr.io \
  --docker-username=<user> --docker-password=<token> \
  -n pgbench
oc patch sa/default -n pgbench \
  -p '{"imagePullSecrets":[{"name":"ghcr"}]}'
```

## Configuration

Every property can be set in `application.yml`, as a command line argument
(`--pgbench.interval=30s`) or as an environment variable (`PGBENCH_INTERVAL=30s`). The password
should be given as environment variable, it is passed to the process as `PGPASSWORD` and never
appears in a command line or a log message.

| Property | Default | Description |
| --- | --- | --- |
| `pgbench.enabled` | `true` | `false` exposes the endpoints without running a benchmark |
| `pgbench.interval` | `60s` | delay between two runs |
| `pgbench.initial-delay` | `10s` | delay before the first run after startup |
| `pgbench.timeout` | `90s` | hard limit for a single pgbench process |
| `pgbench.verify-binary` | `true` | warn at startup when the binary is missing |
| `pgbench.connection.host` | `localhost` | database host, `-h` |
| `pgbench.connection.port` | `5432` | database port, `-p` |
| `pgbench.connection.database` | `postgres` | database name, `-d` |
| `pgbench.connection.username` | `postgres` | database user, `-U` |
| `pgbench.connection.password` | empty | `PGPASSWORD`, also read from `PGBENCH_PASSWORD` |
| `pgbench.connection.sslmode` | empty | `PGSSLMODE` |
| `pgbench.connection.connect-timeout` | `10s` | `PGCONNECT_TIMEOUT` |
| `pgbench.connection.options` | empty | map, exported as `PG<KEY>`, e.g. `options.replication: true` |
| `pgbench.workload.binary` | `pgbench` | path to the executable |
| `pgbench.workload.mode` | `BUILTIN` | `BUILTIN` or `SCRIPT` |
| `pgbench.workload.builtin-script` | empty | `tpcb-like`, `simple-update`, `select-only`; empty keeps the pgbench default |
| `pgbench.workload.script-path` | empty | SQL script file used by the mode `SCRIPT`, `-f` |
| `pgbench.workload.clients` | `4` | concurrent database sessions, `-c` |
| `pgbench.workload.jobs` | `1` | pgbench worker threads, `-j` |
| `pgbench.workload.duration` | `10s` | run duration in seconds, `-T` |
| `pgbench.workload.transactions` | `0` | transactions per client, `-t`, takes precedence over the duration |
| `pgbench.workload.scale-factor` | `0` | reported scale factor, `-s` |
| `pgbench.workload.vacuum` | `true` | `false` adds `--no-vacuum`, required for custom scripts |
| `pgbench.workload.progress-interval` | `0s` | progress report interval, `-P` |
| `pgbench.workload.query-mode` | empty | `simple`, `extended`, `prepared`, `-M` |
| `pgbench.workload.rate` | `0` | target transactions per second, `-R` |
| `pgbench.workload.max-tries` | `1` | retries for serialization and deadlock errors |
| `pgbench.workload.report-per-command` | `false` | per command statistics, `-r` |
| `pgbench.workload.variables` | empty | map, passed as `-D name=value` |
| `pgbench.workload.extra-arguments` | empty | list, appended to the command line |
| `pgbench.history.max-entries` | `60` | number of runs kept in memory |

Example with a custom script and a five minute interval:

```bash
java -jar target/pgbench-metric-server-0.1.0-SNAPSHOT.jar \
  --pgbench.interval=5m \
  --pgbench.workload.mode=SCRIPT \
  --pgbench.workload.script-path=/etc/pgbench/read-only.sql \
  --pgbench.workload.vacuum=false \
  --pgbench.workload.clients=16 \
  --pgbench.connection.database=shop
```

## Metrics

Scrape `GET /actuator/prometheus`. All gauges describe the **last** run.

| Metric | Description |
| --- | --- |
| `pgbench_tps` | transactions per second |
| `pgbench_latency_average_milliseconds` | average transaction latency |
| `pgbench_latency_stddev_milliseconds` | standard deviation of the transaction latency |
| `pgbench_connection_time_milliseconds` | initial connection time |
| `pgbench_transactions_processed` | processed transactions |
| `pgbench_transactions_failed` | failed transactions |
| `pgbench_transactions_failed_percent` | share of failed transactions |
| `pgbench_clients`, `pgbench_threads` | clients and worker threads of the run |
| `pgbench_scaling_factor` | reported scale factor |
| `pgbench_run_duration_seconds` | benchmark duration reported by pgbench |
| `pgbench_last_run_successful` | `1` when the last run succeeded, otherwise `0` |
| `pgbench_last_run_timestamp_seconds` | start time of the last run |
| `pgbench_last_run_wall_clock_seconds` | wall clock time the process needed |
| `pgbench_runs_total{status}` | finished runs, `status` is `success`, `failed`, `timeout` or `error` |
| `pgbench_info{component,version}` | version of the pgbench client and of the benchmarked server |

A measurement that a run did not produce is exported as `NaN`, for example before the first run or
after a connection error. Guard queries with `pgbench_runs_total` or `pgbench_last_run_successful`
if you do not want to handle `NaN`:

```promql
# TPS averaged over the last hour
avg_over_time(pgbench_tps[1h])

# alert when a run did not succeed
pgbench_last_run_successful == 0 or absent(pgbench_last_run_successful)

# alert when the average latency grows
pgbench_latency_average_milliseconds > 100 and pgbench_last_run_successful == 1
```

Prometheus configuration:

```yaml
scrape_configs:
  - job_name: pgbench
    scrape_interval: 30s
    static_configs:
      - targets: ["pgbench-metric-server:8080"]
```

## REST API

| Endpoint | Description |
| --- | --- |
| `GET /api/pgbench/latest` | result of the last run, `404` while no run has finished |
| `GET /api/pgbench/runs?limit=10` | run history, newest first |
| `POST /api/pgbench/run` | starts a run, `202` accepted, `409` while a run is active |
| `GET /actuator/prometheus` | metrics in the Prometheus text format |
| `GET /actuator/health` | health of the service, independent of the benchmark result |

Example result:

```json
{
  "startedAt": "2026-09-27T18:17:25.736547780Z",
  "finishedAt": "2026-09-27T18:17:35.742100154Z",
  "wallClock": "PT10.005552374S",
  "exitCode": 0,
  "status": "SUCCESS",
  "command": "pgbench -h localhost -p 5432 -U postgres -d mydb -c 4 -j 1 -T 10",
  "successful": true,
  "measurements": {
    "clientVersion": "17.2",
    "serverVersion": "17.2",
    "transactionType": "<builtin: TPC-B (sort of)>",
    "scalingFactor": 1,
    "queryMode": "simple",
    "clients": 4,
    "threads": 1,
    "reportedDurationSeconds": 10,
    "transactionsProcessed": 43210,
    "failedTransactions": 4,
    "failedTransactionsPercent": 0.009,
    "latencyAverageMs": 0.925,
    "latencyStddevMs": 0.31,
    "tps": 4318.55,
    "connectionTimeMs": 7.5
  }
}
```

`status` is one of:

| Status | Meaning |
| --- | --- |
| `SUCCESS` | pgbench exited with `0` |
| `FAILED` | pgbench exited with a non-zero code, for example `1` for a connection error or `2` for an error during the run; partial results are still published |
| `TIMEOUT` | the process was killed after `pgbench.timeout` |
| `ERROR` | the process could not be started or its output could not be read |

A failing benchmark does not make the service unhealthy, `/actuator/health` stays `200`. Use
`pgbench_last_run_successful` to alert on a failing database instead.

## Workload modes

**`BUILTIN`** uses a script that ships with pgbench:

```yaml
pgbench:
  workload:
    mode: BUILTIN
    builtin-script: select-only   # tpcb-like, simple-update or select-only
    scale-factor: 10
```

**`SCRIPT`** runs a custom SQL file, the file counts as one transaction per execution:

```sql
-- /etc/pgbench/read-only.sql
\set aid random(1, 100000)
SELECT abalance FROM pgbench_accounts WHERE aid = :aid;
```

```yaml
pgbench:
  workload:
    mode: SCRIPT
    script-path: /etc/pgbench/read-only.sql
    vacuum: false
```

Variables for the script are passed with `pgbench.workload.variables`, for example
`variables: {account_type: premium}` becomes `-D account_type=premium`. Everything that the
configuration does not cover can be appended with `pgbench.workload.extra-arguments`, for example
`extra-arguments: ["--aggregate-interval=1"]`. Keep `pgbench.timeout` larger than
`pgbench.workload.duration` plus a margin, otherwise the process is killed before pgbench can
print its result.

## Behaviour notes

- only one run is active at a time, a trigger during a running benchmark is skipped, so runs never
  overlap; with a run duration longer than the interval the effective interval grows accordingly
- `POST /api/pgbench/run` returns `409` instead of queueing a second benchmark
- stdout and stderr of pgbench are merged, progress reports on stderr are ignored by the parser
- a run is published even when pgbench exits non-zero, because pgbench prints partial results then
- on shutdown the service waits for a running benchmark to finish

## Development

```bash
./mvnw test        # unit tests and an end to end test with a pgbench stub, no database needed
./mvnw spring-boot:run
```

The end to end test writes a stub script that prints a realistic pgbench result, so the scheduled
run, the REST API and the Prometheus endpoint are verified without PostgreSQL.

## License

Apache License 2.0, see [LICENSE](LICENSE).
