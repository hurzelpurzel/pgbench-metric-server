# AGENTS.md

## Project

Single-module Spring Boot 4.1.1 service (Java 25, Maven wrapper 3.9.11). It shells out to the
`pgbench` CLI as a child process — there is deliberately **no JDBC driver and no embedded
database**. Package root: `de.pgbench.metricserver`.

`README.md` is the user-facing reference (property table, metric table, REST API, example JSON).
Any change to configuration, metric names, or the JSON shape must be mirrored there.

## Commands

```bash
./mvnw test                                     # 40 tests, ~8s
./mvnw test -Dtest=PgbenchOutputParserTest      # single class
./mvnw test -Dtest=PgbenchOutputParserTest#parsesTransactionRun
./mvnw -o test                                  # offline works, deps are in ~/.m2
./mvnw package                                  # -> java -jar target/pgbench-metric-server-0.1.0-SNAPSHOT.jar
./mvnw spring-boot:run
```

- `./mvnw test` is the **only** gate: no spotless/checkstyle/formatter plugin, no `.editorconfig`,
  no CI workflow, no separate lint or typecheck step. Compilation is the typecheck.
- `podman build .` runs the same 40 tests inside the image build (`-DskipTests` via
  `--build-arg MAVEN_ARGS=-DskipTests` skips them), so the Dockerfile is a second test gate.
- `pgbench` is **not installed on this machine**. `PgbenchBinaryVerifier` only logs a warning, so
  local runs end with status `ERROR` / `exitCode -1`. That is the environment, not a regression.
  For a live run without PostgreSQL, point `--pgbench.workload.binary` at a shell script stub.

## Execution flow

`PgbenchScheduler` (`@Scheduled` fixedDelay) or `POST /api/pgbench/run` -> `PgbenchService.trigger()`
-> single-thread `pgbenchExecutor` bean -> `PgbenchCommandBuilder` (properties -> argv + env) ->
`ProcessRunner`/`DefaultProcessRunner` (merged stdout+stderr, killed at `pgbench.timeout`) ->
`PgbenchOutputParser` -> `PgbenchService.publish()` -> `PgbenchResultStore.record()` +
`PgbenchMetrics.recordRun()`.

Packages: `config` (properties, command builder, binary verifier, executor), `process`
(`ProcessRunner` interface + default impl), `parse`, `service`, `metrics`, `web`, `model`.

Every gauge is backed by `PgbenchResultStore.latest()`; `PgbenchMetrics` keeps no per-run state.
Record into the store **before** `recordRun`, otherwise the meters describe the previous run.

## Invariants that are easy to break

1. **The password must never reach the command line.** It is passed as `PGPASSWORD`
   (`PgbenchCommandBuilder.buildEnvironment`). `PgbenchRun.command` is returned by the REST API
   and written to the log, and both the command builder test and the integration test assert the
   password is absent from it.
2. **A missing measurement is `NaN`, never `0`** (`sanitize`/`boxed` in `PgbenchMetrics`). NaN is
   the documented "no run yet / value not reported" signal; do not substitute a default.
3. `pgbench.timeout` must stay clearly larger than the workload duration, otherwise the process is
   killed before pgbench prints its result. Shutdown budget is derived from it: executor awaits
   `timeout + 30s`, `spring.lifecycle.timeout-per-shutdown-phase: 2m`, `DefaultProcessRunner` has
   5s destroy/force-kill graces and a 128 KiB output tail cap.
4. **At most one run at a time**: `PgbenchService.running` (`AtomicBoolean`) plus core/max pool 1
   and queue capacity 1. `POST /api/pgbench/run` returns 409 while a run is active and 503 when the
   executor is already shutting down. The scheduler uses `fixedDelay`, so a run longer than the
   interval stretches the effective interval instead of overlapping.
5. `pgbench.enabled=false` drops `PgbenchScheduler` and `PgbenchBinaryVerifier` (both
   `@ConditionalOnProperty`) but keeps the REST API and the metrics.
6. The parser is line-oriented regex with **last occurrence wins** (the final result block).
   `parseErrors` collects `error|fatal|critical|warning|hint` lines and deliberately skips
   `progress:` reports and the `pgbench (...)` banner. Adding a parsed field means a new `Pattern`
   plus a realistic text-block fixture in `PgbenchOutputParserTest`.
7. Jackson runs with `default-property-inclusion: non_null`, and `PgbenchMeasurements.isEmpty()` is
   `@JsonIgnore`d. Adding a record component changes the REST JSON (and the README example).
8. Micrometer names are dotted and rely on `baseUnit` to become the documented Prometheus names
   (`pgbench_latency_average_milliseconds`). The `PgbenchMetrics` constants are `public` and used
   directly by tests — rename them only together with the README metric table.

## Adding a configuration property

1. `PgbenchProperties`: field + accessors. `options`, `variables` and `extraArguments` are `final`
   and bound in place — they deliberately have no setter. Validation uses Jakarta annotations
   (`@Validated`, `@NotNull`, `@Min`, `@NotBlank`).
2. `src/main/resources/application.yml`: every entry carries an explanatory comment and the
   default must match the field default in the class.
3. `README.md` property table.
4. If it maps to pgbench: `PgbenchCommandBuilder` (argv or `PG*` env var) plus
   `PgbenchCommandBuilderTest`. Note `-t` takes precedence over `-T`, and empty/blank values are
   omitted entirely.

## Container image (`Dockerfile`)

Multi-stage: `ubi10/openjdk-25` compiles with the Maven wrapper (it downloads its own distribution,
so no Maven has to be installed), `ubi10/ubi-minimal` is the runtime. Things that are easy to break:

1. **`pgbench` is not in the UBI 10 repositories** (the `postgresql` package there is 16 and has no
   `pgbench` binary, and there is no `postgresql-contrib`). The client tools come from the PGDG
   repository, installed in `microdnf` and placed on the `PATH` as `/usr/pgsql-<major>/bin`. The
   repo file is deleted again after the install. `PGDG_VERSION` and `EL_VERSION` are build args, the
   package name and the `PATH` entry are derived from the former.
2. **`JAVA_TOOL_OPTIONS`, not `JAVA_OPTS`.** The Spring Boot 4 executable jar launcher ignores
   `JAVA_OPTS`; only the JVM-native variable is applied. Verified with `jcmd 1 VM.flags` under
   `--memory=1g`: `-XX:MaxRAMPercentage=75.0` has to give `MaxHeapSize=805306368`.
3. **Non-root and arbitrary UID**: the runtime stage ends with `USER 1001:0`, `/opt/app` is
   `chgrp 0` + `chmod -R g=u`, which is what lets an OpenShift random UID in group 0 read the jar
   and write `HOME=/opt/app`.
4. `ENTRYPOINT` is exec form, so the JVM is pid 1 and gets `SIGTERM` directly; `STOPSIGNAL SIGTERM`
   and `server.shutdown: graceful` together await a running pgbench. Documented consequence: a pod
   needs `terminationGracePeriodSeconds` above `spring.lifecycle.timeout-per-shutdown-phase`.
5. `io.openshift.expose-services="8080:8080"` makes `oc new-app --image=...` create a Route.
6. `.dockerignore` denies the context with `*` and re-allows only `pom.xml`, `mvnw`, `.mvn`, `src`;
   the jar is built inside the image, `target/` must never be part of the context.

Publish flow: `podman login -u <user> -p <token> ghcr.io` with a token that has `write:packages`
(the default `gh auth token` does **not** have that scope), then tag
`ghcr.io/hurzelpurzel/pgbench-metric-server:<version>` plus `latest` and `sha-<commit>` and
`podman push` them.

## Testing conventions

- **No mocking framework.** `ProcessRunner` is a functional interface, so fakes are lambdas:
  `(command, environment, timeout) -> new ProcessOutcome(0, OUTPUT, Duration.ofSeconds(10), false)`.
  `PgbenchService` is constructed by hand with `Runnable::run` as the executor to keep it
  synchronous and deterministic.
- Metrics tests use Micrometer's `SimpleMeterRegistry`, never the Spring context.
- `PgbenchMetricServerIntegrationTest` boots the full app on a random port with a `/bin/sh` stub
  script written to a temp file and `pgbench.workload.binary` pointed at it — **no PostgreSQL
  required**. Config is injected with `@DynamicPropertySource` (no test `application.yml`).
  The context is cached per class, so run history accumulates across test methods; assertions poll
  `/api/pgbench/latest` with a 20s deadline rather than sleeping.
- Style: package-private test classes and methods, an English `@DisplayName` on every test,
  AssertJ `assertThat` only, `within(1e-9)` for doubles, Java text blocks for pgbench output.

## Code style

4-space indent, 120-column lines, explicit imports, javadoc on types and non-obvious methods
explaining *why*. No formatter is configured, so match the surrounding file rather than reformatting.
