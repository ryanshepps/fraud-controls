# controls-platform

Kotlin fraud controls service skeleton for consuming `fraudgen` events and
turning them into allow, challenge, hold, or deny decisions.

The current staged platform contains the Gradle multi-module layout, pure core
domain models, fraudgen event parsing, a provider-based feature resolver with
request-scoped caching, pluggable scoring contracts, a YAML rule DSL with typed
evaluation, decision orchestration, Micrometer/Prometheus observability, shadow
evaluation reporting, and versioned runtime contracts for Kafka decision events,
rule evaluation events, shadow evaluation events, and DynamoDB audit rows. The
local dependency stack includes Redpanda, DynamoDB Local, Redis, a deterministic
demo scoring sidecar, Prometheus, and Grafana, and avoids common host-port
collisions by exposing DynamoDB on `18000` and Grafana on `13000`.

Runtime payload compatibility rules are documented in
[docs/runtime-contracts.md](docs/runtime-contracts.md).

## Build

```bash
mise install
mise exec -- ./gradlew test
```

## Local Dependencies

```bash
docker compose up --build
docker compose down
```

`docker compose up --build` starts Redpanda, DynamoDB Local, Redis, the
deterministic demo scoring sidecar, the controls runtime, a `fraudgen-feed`
container, Prometheus, and Grafana. The controls runtime starts an admin API on
`http://localhost:18080`. The `fraudgen-feed` container runs the real sibling
`fraudgen` CLI against `examples/controls-demo-stream.yaml` and continuously
publishes realistic P2P transaction events to the `transactions` Kafka topic
plus ground-truth labels to `fraud_labels`.

Useful local checks:

```bash
curl -s http://localhost:18080/rules
curl -s http://localhost:18080/metrics | grep controls_decisions_total
docker compose logs fraudgen-feed | tail
curl -s http://localhost:18080/decisions/{event_id_from_logs}
```

Grafana is available at `http://localhost:13000` with username `admin` and
password `admin`. Open the `Controls Platform Observability` dashboard to watch
decision counts, rule fire rates, shadow-rule metrics, and live-vs-shadow score
divergence move as the feed runs. The fraudgen demo mix is intentionally
allow-heavy, with a small number of new-account cashout and card-testing
scenario events so deny and hold paths are visible without making the traffic
look dominated by fraud.

The demo scoring sidecar exposes `GET /health` and `POST /score` on port
`50051`. It is deterministic demo infrastructure for the local runtime; the real
XGBoost scoring sidecar remains a separate implementation step.

Prometheus scrapes the admin API on `/metrics`, and Grafana provisions the
`Controls Platform Observability` dashboard from
`docker-compose/grafana/provisioning/dashboards`.

## Rule Lifecycle Demo

```bash
curl -s -X POST http://localhost:18080/rules/demo-score-shadow/promote \
  -H 'content-type: application/json' \
  -d '{"actor":"demo","confirm":true}'

curl -s -X POST http://localhost:18080/rules/demo-score-shadow/disable \
  -H 'content-type: application/json' \
  -d '{"actor":"demo"}'
```

The feed keeps running while rules change, so the dashboard and `/decisions/*`
reflect the promoted or disabled rule without restarting the stack.
