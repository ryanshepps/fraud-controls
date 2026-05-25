# controls-platform

Kotlin fraud controls service skeleton for consuming `fraudgen` events and
turning them into allow, challenge, hold, or deny decisions.

The current staged platform contains the Gradle multi-module layout, pure core
domain models, fraudgen event parsing, and a provider-based feature resolver with
request-scoped caching, plus typed rule evaluation. The local dependency stack
includes Redpanda, DynamoDB Local, Redis, Prometheus, and Grafana, and avoids
common host-port collisions by exposing DynamoDB on `18000` and Grafana on
`13000`.

## Build

```bash
mise install
mise exec -- ./gradlew test
```

## Local Dependencies

```bash
docker compose up -d --wait
docker compose down
```
