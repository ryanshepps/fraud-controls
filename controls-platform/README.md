# controls-platform

Kotlin fraud controls service skeleton for consuming `fraudgen` events and
turning them into allow, challenge, hold, or deny decisions.

The current staged platform contains the Gradle multi-module layout, pure core
domain models, fraudgen event parsing, a provider-based feature resolver with
request-scoped caching, pluggable scoring contracts, a YAML rule DSL with typed
evaluation, decision orchestration, Micrometer/Prometheus observability, shadow
evaluation reporting, and versioned runtime contracts for Kafka decision events,
rule evaluation events, shadow evaluation events, and DynamoDB audit rows. The local dependency stack
includes Redpanda, DynamoDB Local, Redis, Prometheus, and Grafana, and avoids
common host-port collisions by exposing DynamoDB on `18000` and Grafana on
`13000`.

Runtime payload compatibility rules are documented in
[docs/runtime-contracts.md](docs/runtime-contracts.md).

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

Prometheus scrapes the admin API on `/metrics`, and Grafana provisions the
`Controls Platform Observability` dashboard from
`docker-compose/grafana/provisioning/dashboards`.
