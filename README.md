# fraud-controls

Repository for the fraud traffic generator and the controls platform that consumes
its events.

## Projects

- [fraudgencli](fraudgencli/) - Python CLI for deterministic synthetic P2P fraud
  traffic generation.
- [controls-platform](controls-platform/) - Kotlin fraud controls platform for
  parsing fraudgen events and producing allow, challenge, hold, or deny decisions.

## Quick Start

Run the full local controls demo:

```bash
cd controls-platform
docker compose up --build
```

Then open Grafana at `http://localhost:13000` or query the admin API at
`http://localhost:18080`. The compose stack includes a `fraudgen-feed` container
that runs the real `fraudgen` CLI continuously into Kafka.

Run fraudgen manually from its project directory:

```bash
cd fraudgencli
uv run fraudgen run-config --config examples/stage3-run.yaml
uv run fraudgen stream-config --config examples/controls-demo-stream.yaml --bootstrap-servers localhost:19092 --topic transactions --label-topic fraud_labels --loop --delay-ms 500
```

Run controls-platform tests from its project directory:

```bash
cd controls-platform
mise exec -- ./gradlew test
```
