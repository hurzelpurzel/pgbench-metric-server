# Local monitoring stack

A self-contained demo of `pgbench-metric-server` with its metrics being scraped and visualized:
PostgreSQL 17, the metric server built from the repository `Dockerfile`, a Prometheus that scrapes
it and a Grafana with a provisioned dashboard. No local JDK, `pgbench` or database is needed.

```
monitoring/
├── compose.yaml                      # postgres + pgbench-init + metric server + prometheus + grafana
├── prometheus.yml                    # scrape configuration, mounted into Prometheus
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/prometheus.yml  # Prometheus datasource, uid "prometheus"
│   │   └── dashboards/dashboards.yml   # dashboard provider, reads the directory below
│   └── dashboards/
│       └── pgbench-overview.json       # the provisioned dashboard
└── README.md
```

## Quick start

From the repository root:

```bash
podman-compose -f monitoring/compose.yaml up -d --build
```

`--build` builds the image from the repository `Dockerfile`, so the stack always runs the current
sources. Drop it to reuse the existing `localhost/pgbench-metric-server:0.1.0-SNAPSHOT` image:

```bash
podman-compose -f monitoring/compose.yaml up -d
```

The stack is named `pgbench-monitoring`, so it does not collide with other compose projects.
Startup order is handled by compose: `pgbench-init` waits for a healthy `postgres` and runs
`pgbench -i` once, the metric server starts after the initializer finished successfully. The first
benchmark starts five seconds later and then repeats every 20 seconds. The initializer is
idempotent, it skips the setup when the pgbench tables already exist, so restarting the stack does
not drop the tables underneath a running benchmark.

| URL | Content |
| --- | --- |
| <http://localhost:8080/actuator/health> | health of the metric server |
| <http://localhost:8080/api/pgbench/latest> | last run as JSON |
| <http://localhost:8080/actuator/prometheus> | raw Prometheus metrics |
| <http://localhost:9090> | Prometheus UI, e.g. query `pgbench_tps` |
| <http://localhost:3000> | Grafana, opens the provisioned dashboard directly |

Useful PromQL queries:

```promql
# current values of the last run
pgbench_tps
pgbench_latency_average_milliseconds

# runs per status over the last ten minutes
sum by (status) (increase(pgbench_runs_total[10m]))

# scrape target health
up{job="pgbench-metric-server"}
```

## Dashboard

Grafana opens on the `pgbench-metric-server` dashboard, it is provisioned from
`grafana/dashboards/pgbench-overview.json` into the `pgbench` folder. Anonymous access is enabled
as `Viewer`, so no login is needed for viewing; the admin login is `admin` with the password from
`GRAFANA_ADMIN_PASSWORD` (default `admin`).

| Row | Panels |
| --- | --- |
| last run | TPS, average latency, processed and failed transactions |
| run state | success flag, start time, duration, clients, threads, scale factor, scrape target |
| time series | TPS, latency and connection time, wall clock vs. reported duration |
| history | runs per status as stacked bars, processed and failed transactions per run |
| versions | `pgbench_info` labels, i.e. client and server version |

The panels use the same queries as the PromQL examples above. `pgbench_latency_stddev_milliseconds`
is deliberately not shown: pgbench 17 no longer prints the latency standard deviation, so the
gauge is always `NaN` with the default output. The start time panel multiplies
`pgbench_last_run_timestamp_seconds` by 1000 because Grafana's date units expect epoch
milliseconds - without that it renders the epoch seconds as a date in 1970.

The dashboard is read-only on purpose (`allowUiUpdates: false`), edits in the UI are lost on
restart. Change the JSON file and reload Grafana, or copy the panel JSON into a new dashboard.

## Configuration

The metric server is configured through environment variables in `compose.yaml`, using the same
properties as a normal deployment (see the property table in the top-level `README.md`). The
defaults are shortened for the demo:

| Variable | Value | Property |
| --- | --- | --- |
| `PGBENCH_INTERVAL` | `20s` | `pgbench.interval` |
| `PGBENCH_INITIAL_DELAY` | `5s` | `pgbench.initial-delay` |
| `PGBENCH_WORKLOAD_DURATION` | `5s` | `pgbench.workload.duration` |
| `PGBENCH_WORKLOAD_CLIENTS` | `4` | `pgbench.workload.clients` |

The database password defaults to `secret`. Override it for both containers with an environment
variable or a `.env` file next to `compose.yaml`:

```bash
POSTGRES_PASSWORD=change-me podman-compose -f monitoring/compose.yaml up -d
```

The Grafana admin password defaults to `admin` and can be changed the same way with
`GRAFANA_ADMIN_PASSWORD`. Anonymous viewers keep read access either way.

`prometheus.yml` uses the compose service name as target and a 15s scrape interval. Keep the
scrape interval below `pgbench.interval`, otherwise a run can finish between two scrapes and its
gauges are never collected. Prometheus is told to keep seven days of data
(`--storage.tsdb.retention.time`), the series live in the `prometheusdata` volume and survive a
`down`/`up`.

## Logs, reset and teardown

```bash
podman-compose -f monitoring/compose.yaml logs -f pgbench-metric-server
podman-compose -f monitoring/compose.yaml ps
```

The PostgreSQL data lives in the `pgdata` volume, so the pgbench tables stay initialized across
restarts, Prometheus and Grafana keep their state in `prometheusdata` and `grafanadata`. Remove the
volumes to start from an empty database and empty history:

```bash
podman-compose -f monitoring/compose.yaml down -v
```

`down -v` deletes the benchmark history with the volumes, `down` keeps it. Provisioned dashboards
are not affected, they come from this directory.

## Troubleshooting

- **`health=down` for the scrape target** - check `podman-compose ... logs pgbench-metric-server`;
  the target only exists once the container listens on 8080.
- **Runs fail with a connection error** - the metric server uses `PGBENCH_CONNECTION_PASSWORD`,
  `pgbench-init` uses `PGPASSWORD`. Both read `POSTGRES_PASSWORD`, so set the override for both,
  or edit `compose.yaml`.
- **Runs fail with `relation "pgbench_accounts" does not exist`** - `pgbench-init` did not run,
  for example after a volume reset. Re-run the initializer:
  `podman-compose -f monitoring/compose.yaml run --rm pgbench-init`.
- **Grafana shows "No data"** - check `podman-compose ... logs grafana` and the datasource under
  *Connections > Data sources* (uid `prometheus`); the dashboard relies on the provisioned
  datasource with exactly that uid.
