# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk21 AS build

WORKDIR /workspace

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew gradlew.bat ./
COPY proto ./proto
COPY modules/api/build.gradle.kts modules/api/
COPY modules/core/build.gradle.kts modules/core/
COPY modules/decisioning/build.gradle.kts modules/decisioning/
COPY modules/demo/build.gradle.kts modules/demo/
COPY modules/features/build.gradle.kts modules/features/
COPY modules/observability/build.gradle.kts modules/observability/
COPY modules/persistence/build.gradle.kts modules/persistence/
COPY modules/rules/build.gradle.kts modules/rules/
COPY modules/scoring/build.gradle.kts modules/scoring/
COPY modules/streaming/build.gradle.kts modules/streaming/
COPY modules/testing/build.gradle.kts modules/testing/

RUN --mount=type=cache,target=/home/gradle/.gradle/caches,uid=1000,gid=1000 \
    --mount=type=cache,target=/home/gradle/.gradle/wrapper,uid=1000,gid=1000 \
    gradle :modules:demo:dependencies --no-daemon

COPY modules/api/src modules/api/src
COPY modules/core/src modules/core/src
COPY modules/decisioning/src modules/decisioning/src
COPY modules/demo/src modules/demo/src
COPY modules/features/src modules/features/src
COPY modules/observability/src modules/observability/src
COPY modules/persistence/src modules/persistence/src
COPY modules/rules/src modules/rules/src
COPY modules/scoring/src modules/scoring/src
COPY modules/streaming/src modules/streaming/src
COPY configs ./configs

RUN --mount=type=cache,target=/home/gradle/.gradle/caches,uid=1000,gid=1000 \
    --mount=type=cache,target=/home/gradle/.gradle/wrapper,uid=1000,gid=1000 \
    gradle :modules:demo:installDist --no-daemon

FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/modules/demo/build/install/demo ./
COPY --from=build /workspace/configs ./configs

EXPOSE 8080

ENTRYPOINT ["/app/bin/demo"]
