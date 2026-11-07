# pgbench-metric-server Helm chart

Deploys [pgbench-metric-server](../README.md) as a Kubernetes `Deployment` and exposes it
through a `Service`. The chart optionally creates the database password `Secret` and a Prometheus
Operator `ServiceMonitor`.

## Prerequisites

- Kubernetes 1.23 or newer, Helm 3.8 or newer
- a PostgreSQL instance reachable from the cluster
- initialized pgbench tables (`pgbench -i`) when `pgbench.workload.mode` is `BUILTIN`
- an image pull secret when the ghcr.io package is private

## Install from the OCI registry

Released chart versions are published as OCI artifacts:

```bash
helm install pgbench oci://ghcr.io/hurzelpurzel/charts/pgbench-metric-server \
  --version 0.1.0 \
  --namespace pgbench --create-namespace \
  --set pgbench.connection.host=postgres.default.svc.cluster.local \
  --set pgbench.connection.existingSecret=pgbench
```

Inspect a published chart with `helm show values oci://ghcr.io/hurzelpurzel/charts/pgbench-metric-server --version 0.1.0`.

## Install from this repository

```bash
helm install pgbench ./helm \
  --namespace pgbench --create-namespace \
  --set pgbench.connection.host=postgres.default.svc.cluster.local \
  --set pgbench.connection.database=postgres \
  --set pgbench.connection.password=secret
```

The password should not live in a shell history or a values file in production. Create the
`Secret` yourself and reference it instead:

```bash
kubectl create secret generic pgbench-db \
  --namespace pgbench \
  --from-literal=PGBENCH_PASSWORD=secret

helm install pgbench ./helm \
  --namespace pgbench \
  --set pgbench.connection.existingSecret=pgbench-db
```

Existing secrets from the [root README](../README.md#openshift) use the key `PGBENCH_PASSWORD`
by default; adapt `pgbench.connection.passwordKey` when your key has another name. When
`existingSecret` is set, `pgbench.connection.password` is ignored. Rotating the content of an
existing Secret needs a restart to reach the running pod: `kubectl rollout restart deploy/<release>-pgbench-metric-server`.

After the rollout, use the endpoints directly or run a one-off benchmark:

```bash
kubectl -n pgbench port-forward svc/pgbench-pgbench-metric-server 8080:8080
curl http://localhost:8080/api/pgbench/latest
curl http://localhost:8080/actuator/prometheus
curl -X POST http://localhost:8080/api/pgbench/run
```

## A production-oriented values file

```yaml
image:
  tag: 0.1.0
imagePullSecrets:
  - name: ghcr

terminationGracePeriodSeconds: 420   # above pgbench.timeout (6m) plus shutdown margin

pgbench:
  interval: 5m
  timeout: 6m
  connection:
    host: postgres.default.svc.cluster.local
    database: shop
    username: benchmark
    existingSecret: pgbench-db
  workload:
    mode: BUILTIN
    builtinScript: select-only
    clients: 16
    jobs: 4
    duration: 5m

serviceMonitor:
  enabled: true
  labels:
    release: kube-prometheus-stack

resources:
  requests:
    cpu: 250m
    memory: 256Mi
  limits:
    memory: 512Mi
```

Keep `pgbench.timeout` clearly above `pgbench.workload.duration`; otherwise the process is killed
before it prints a result. `pgbench.workload.transactions` takes precedence over the duration.

## Values

### Image and deployment

| Value | Default | Description |
| --- | --- | --- |
| `image.repository` | `ghcr.io/hurzelpurzel/pgbench-metric-server` | image repository |
| `image.tag` | chart `appVersion` | image tag |
| `image.pullPolicy` | `IfNotPresent` | image pull policy |
| `imagePullSecrets` | `[]` | pull secrets for a private image |
| `replicaCount` | `1` | replicas; each one runs its own benchmark schedule |
| `deploymentStrategy.type` | `Recreate` | avoids two pods benchmarking during a rollout |
| `revisionHistoryLimit` | `3` | retained ReplicaSets |
| `terminationGracePeriodSeconds` | `150` | must exceed the 2 minute graceful shutdown |
| `serviceAccount.create` | `true` | create a dedicated ServiceAccount |
| `serviceAccount.name` | generated | existing or generated ServiceAccount name |
| `serviceAccount.automountServiceAccountToken` | `false` | the application needs no API access |
| `service.type` | `ClusterIP` | service type |
| `service.port` | `8080` | service port, target port is `http` |
| `service.annotations` | `{}` | service annotations |
| `commonLabels`, `commonAnnotations` | `{}` | added to every resource |
| `podLabels`, `podAnnotations` | `{}` | added to the pod template |
| `nameOverride`, `fullnameOverride` | empty | override the generated names |
| `extraArgs` | `[]` | additional Spring Boot command line arguments |
| `extraEnv`, `extraEnvFrom` | `[]` | additional environment sources |
| `jvm.toolOptions` | empty | replaces `JAVA_TOOL_OPTIONS` from the image |
| `resources` | 100m/256Mi requests, 512Mi limit | container resources |
| `nodeSelector`, `tolerations`, `affinity` | empty | scheduling constraints |
| `topologySpreadConstraints` | `[]` | topology spread rules |
| `priorityClassName`, `schedulerName` | empty | pod scheduling options |
| `extraVolumes`, `extraVolumeMounts` | `[]` | additional mounts, e.g. a SQL script |

### pgbench configuration

Every scalar is passed as the matching `PGBENCH_*` environment variable and has the same meaning
as in the [configuration table](../README.md#configuration).

| Value | Default |
| --- | --- |
| `pgbench.enabled` | `true` |
| `pgbench.interval` | `60s` |
| `pgbench.initialDelay` | `10s` |
| `pgbench.timeout` | `90s` |
| `pgbench.verifyBinary` | `true` |
| `pgbench.connection.host` | `localhost` |
| `pgbench.connection.port` | `5432` |
| `pgbench.connection.database` | `postgres` |
| `pgbench.connection.username` | `postgres` |
| `pgbench.connection.password` | empty |
| `pgbench.connection.existingSecret` | empty |
| `pgbench.connection.passwordKey` | `PGBENCH_PASSWORD` |
| `pgbench.connection.sslmode` | empty |
| `pgbench.connection.connectTimeout` | `10s` |
| `pgbench.connection.options` | `{}` |
| `pgbench.workload.binary` | `pgbench` |
| `pgbench.workload.mode` | `BUILTIN` |
| `pgbench.workload.builtinScript` | empty |
| `pgbench.workload.scriptPath` | empty |
| `pgbench.workload.clients` | `4` |
| `pgbench.workload.jobs` | `1` |
| `pgbench.workload.duration` | `10s` |
| `pgbench.workload.transactions` | `0` |
| `pgbench.workload.scaleFactor` | `0` |
| `pgbench.workload.vacuum` | `true` |
| `pgbench.workload.progressInterval` | `0s` |
| `pgbench.workload.queryMode` | empty |
| `pgbench.workload.rate` | `0` |
| `pgbench.workload.maxTries` | `1` |
| `pgbench.workload.reportPerCommand` | `false` |
| `pgbench.workload.variables` | `{}` |
| `pgbench.workload.extraArguments` | `[]` |
| `pgbench.history.maxEntries` | `60` |

`pgbench.connection.options` and `pgbench.workload.variables` are rendered as Spring Boot
command line arguments with bracket notation, so keys are preserved:

```yaml
pgbench:
  connection:
    options:
      application_name: my-bench
      keepalives_idle: "30"
  workload:
    variables:
      account_type: premium
```

This becomes `--pgbench.connection.options[application_name]=my-bench` and so on. Raw pgbench
arguments belong into `pgbench.workload.extraArguments`.

### Probes and security

| Value | Default |
| --- | --- |
| `livenessProbe` | HTTP `/actuator/health/liveness` |
| `readinessProbe` | HTTP `/actuator/health/readiness` |
| `startupProbe` | HTTP `/actuator/health/liveness`, 30 failures |
| `podSecurityContext` | `runAsNonRoot`, `RuntimeDefault` seccomp |
| `securityContext` | no privilege escalation, read-only root, all capabilities dropped |

The pod intentionally has no fixed `runAsUser`: the image runs as UID 1001, while OpenShift assigns
a random UID in group 0. Both work with the same manifest. The Deployment always mounts a writable
`/tmp` emptyDir, so `readOnlyRootFilesystem: true` is safe for the JVM.

## Prometheus

Enable the Prometheus Operator integration when the CRDs are installed:

```yaml
serviceMonitor:
  enabled: true
  labels:
    release: kube-prometheus-stack   # match your serviceMonitorSelector
```

| Value | Default | Description |
| --- | --- | --- |
| `serviceMonitor.enabled` | `false` | create a `ServiceMonitor` |
| `serviceMonitor.labels` | `{}` | additional labels for operator selection |
| `serviceMonitor.path` | `/actuator/prometheus` | metrics path |
| `serviceMonitor.interval` | `30s` | scrape interval |
| `serviceMonitor.scrapeTimeout` | `10s` | scrape timeout, below the interval |
| `serviceMonitor.relabelings` | `[]` | Prometheus relabeling rules |

Without the operator, scrape `pgbench-pgbench-metric-server:8080/actuator/prometheus` or
`<fullname>.<namespace>.svc:8080` directly.

## Custom SQL script

Mount the script with a ConfigMap. The chart does not create the ConfigMap because the file content
and its location are deployment specific:

```bash
kubectl -n pgbench create configmap pgbench-script \
  --from-file=read-only.sql=./read-only.sql
```

```yaml
pgbench:
  workload:
    mode: SCRIPT
    scriptPath: /scripts/read-only.sql
    vacuum: false

extraVolumes:
  - name: script
    configMap:
      name: pgbench-script
extraVolumeMounts:
  - name: script
    mountPath: /scripts
    readOnly: true
```

## Scaling and lifecycle

- Every replica has its own in-memory history and its own schedule. Raising `replicaCount`
  multiplies the load on the database; it does not shard or coordinate runs.
- `deploymentStrategy.type: Recreate` is the default so a rollout never runs two benchmarks at once.
  With `RollingUpdate`, set `maxSurge: 0` and `maxUnavailable: 1`.
- A run is awaited during shutdown. `terminationGracePeriodSeconds` (150) is above
  `spring.lifecycle.timeout-per-shutdown-phase` (120), so a running pgbench can finish after
  `SIGTERM`. Raise it when you raise `pgbench.timeout` substantially.
- The application health endpoint does not depend on the benchmark result. A failing database is
  visible through `pgbench_last_run_successful`, not through the pod readiness.

## Publishing

`.github/workflows/helm-chart.yml` publishes the chart for `v*` tags. Bump `version` and
`appVersion` in `Chart.yaml` together with `pom.xml` before creating the tag; the workflow rejects
a tag that does not match the chart version. Manual dispatch publishes the chart version of the
selected branch and is intended for the first publication or a retry.

## Development


```bash
helm lint helm --strict
helm template pgbench helm
helm template pgbench helm -f my-values.yaml
helm install pgbench helm --dry-run --debug
```
