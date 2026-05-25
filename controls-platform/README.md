# controls-platform

Kotlin fraud controls service skeleton for consuming `fraudgen` events and
turning them into allow, challenge, hold, or deny decisions.

Stage 1 contains the Gradle multi-module layout, pure core domain models, and a
local dependency stack for Redpanda, DynamoDB Local, Redis, Prometheus, and
Grafana. The local stack avoids common host-port collisions by exposing
DynamoDB on `18000` and Grafana on `13000`.

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
