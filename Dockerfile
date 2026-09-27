# Container image for pgbench-metric-server.
#
# Both stages are based on Red Hat UBI 10, so the image builds and runs on
# OpenShift without an additional subscription. pgbench is not part of the UBI 10
# repositories (the postgresql package there is 16 and has no pgbench binary),
# therefore the client tools are installed from the PostgreSQL upstream PGDG repo.
#
#   podman build -t pgbench-metric-server:0.1.0-SNAPSHOT .
#   podman run --rm -p 8080:8080 -e PGBENCH_PASSWORD=secret pgbench-metric-server:0.1.0-SNAPSHOT
#
# The application itself is configured exclusively through pgbench.* properties,
# environment variables or command line arguments, see README.md.

ARG BUILD_IMAGE=registry.access.redhat.com/ubi10/openjdk-25:latest
ARG RUNTIME_IMAGE=registry.access.redhat.com/ubi10/ubi-minimal:latest
# Major version of the pgbench client, has to match the PGDG repository.
ARG PGDG_VERSION=17
# Major version of the RHEL/UBI release the PGDG repository is built for.
ARG EL_VERSION=10

# ------------------------------------------------------------------ build stage
# openjdk-25 provides the JDK 25 the build needs. The Maven wrapper downloads its
# own distribution, so nothing else has to be installed into this stage.
FROM ${BUILD_IMAGE} AS build
# Passed to "mvnw package", for example -DskipTests to skip the test run.
ARG MAVEN_ARGS=
# The base image runs as its default user, the build needs root for the local
# repository in the cache mount.
USER root
WORKDIR /build
# pom.xml and the wrapper come first so the dependency download can be cached
# independently of the sources.
COPY pom.xml mvnw ./
COPY .mvn/ .mvn/
COPY src/ src/
# The local repository lives in a build cache mount, it survives rebuilds that
# only changed a source file.
RUN --mount=type=cache,target=/root/.m2/repository \
    chmod +x mvnw && ./mvnw -B -ntp package ${MAVEN_ARGS}

# ---------------------------------------------------------------- runtime stage
FROM ${RUNTIME_IMAGE} AS runtime
ARG PGDG_VERSION
ARG EL_VERSION
ARG APP_VERSION=0.1.0-SNAPSHOT
ARG BUILD_DATE=unknown
ARG VCS_REF=unknown
USER root

# pgbench comes from PGDG, the headless JDK from the UBI 10 repositories. Both
# installations are verified against their GPG key, the repository file is
# removed afterwards so the image does not depend on it at runtime.
RUN set -eux; \
    curl -fsSL -o /tmp/pgdg.key https://download.postgresql.org/pub/repos/yum/keys/PGDG-RPM-GPG-KEY-RHEL; \
    rpm --import /tmp/pgdg.key; \
    printf '[pgdg%s]\nname=PostgreSQL PGDG\nbaseurl=https://download.postgresql.org/pub/repos/yum/%s/redhat/rhel-%s-x86_64/\nenabled=1\ngpgcheck=1\ngpgkey=file:///tmp/pgdg.key\n' \
        "${PGDG_VERSION}" "${PGDG_VERSION}" "${EL_VERSION}" > /etc/yum.repos.d/pgdg.repo; \
    microdnf install -y --nodocs --setopt=install_weak_deps=0 \
        java-25-openjdk-headless "postgresql${PGDG_VERSION}"; \
    rm -f /etc/yum.repos.d/pgdg.repo /tmp/pgdg.key; \
    microdnf clean all

ENV PATH="/usr/pgsql-${PGDG_VERSION}/bin:${PATH}" \
    LANG=C.UTF-8 \
    HOME=/opt/app \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

WORKDIR /opt/app
COPY --from=build --chown=0:0 /build/target/pgbench-metric-server-*.jar /opt/app/pgbench-metric-server.jar
# OpenShift starts the container with an arbitrary uid that is a member of group
# 0, so the application directory is owned by root:0 and group writable. The same
# works for uid 1001, which runs as 1001:0.
RUN chmod -R g=u /opt/app

# Non-root, the group 0 is what OpenShift relies on for the random uid.
USER 1001:0
# Spring Boot listens on 8080 by default, see server.port in application.yml.
EXPOSE 8080
# Triggers the graceful shutdown configured in application.yml, which waits for a
# running pgbench process. Give the pod a terminationGracePeriodSeconds above
# spring.lifecycle.timeout-per-shutdown-phase (2m) to let that finish.
STOPSIGNAL SIGTERM

# io.openshift.expose-services makes "oc new-app --image=..." create a Route.
LABEL org.opencontainers.image.title="pgbench-metric-server" \
      org.opencontainers.image.description="Prometheus metrics server that runs pgbench on a schedule" \
      org.opencontainers.image.version="${APP_VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.source="https://github.com/hurzelpurzel/pgbench-metric-server" \
      org.opencontainers.image.licenses="Apache-2.0" \
      io.k8s.display-name="pgbench-metric-server" \
      io.k8s.description="Runs pgbench on a schedule and exposes the result as Prometheus metrics" \
      io.openshift.expose-services="8080:8080"

# Exec form, so the JVM is pid 1 and receives SIGTERM directly. JVM options are
# read from JAVA_TOOL_OPTIONS by the JVM itself, the Spring Boot launcher
# ignores JAVA_OPTS. Every pgbench.* property can be appended as an argument, e.g.
# "--pgbench.interval=30s".
ENTRYPOINT ["java", "-jar", "/opt/app/pgbench-metric-server.jar"]
