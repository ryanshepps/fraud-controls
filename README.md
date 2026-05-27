# fraud-controls

Fraud simulation and controls demo for synthetic peer-to-peer payments traffic.
The repo pairs a Python traffic generator with a Kotlin controls platform that
consumes transaction events, resolves features, computes a risk score used by
rules, evaluates YAML controls, emits allow/challenge/hold/deny decisions, and
stores an audit trail for later reconstruction.

The point of the project is to explore the controls-engineering surface around a
model: rollout safety, calibration, auditability, and operational visibility.

## Projects

- [fraudgencli](fraudgencli/) - Python CLI for deterministic synthetic P2P fraud
  traffic generation.
- [controls-platform](controls-platform/) - Kotlin fraud controls platform for
  parsing fraudgen events and producing allow, challenge, hold, or deny decisions.

## Run The Demo

Start the full local stack:

```bash
cd controls-platform
docker compose up --build
```

Then open:

- Admin API: `http://localhost:18080`
- Grafana: `http://localhost:13000` (`admin` / `admin`)
- Tempo: `http://localhost:3200`

The compose stack starts Redpanda, DynamoDB Local, Redis, Prometheus, Grafana,
Tempo, an OpenTelemetry collector, the controls runtime, a deterministic scoring
sidecar, and a live `fraudgen` feed into Kafka. The Grafana p99 latency panel is
backed by Prometheus exemplars, so sampled decision-latency points can link to
the matching Tempo trace.
