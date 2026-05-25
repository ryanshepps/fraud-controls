# fraud-controls

Repository for the fraud traffic generator and the controls platform that consumes
its events.

## Projects

- [fraudgencli](fraudgencli/) - Python CLI for deterministic synthetic P2P fraud
  traffic generation.
- [controls-platform](controls-platform/) - Kotlin fraud controls platform for
  parsing fraudgen events and producing allow, challenge, hold, or deny decisions.

## Quick Start

Run fraudgen from its project directory:

```bash
cd fraudgencli
uv run fraudgen run-config --config examples/stage3-run.yaml
```

Run controls-platform tests from its project directory:

```bash
cd controls-platform
mise exec -- ./gradlew test
```
