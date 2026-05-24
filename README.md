# fraudgen

Deterministic synthetic P2P traffic generator for a fraud controls platform.

Stage 1 includes the package skeleton, customer population model, archetype-based
baseline simulator, CSV output, and tests for deterministic output. Fraud
scenario injectors, YAML runs, Kafka, Parquet, and full documentation are
planned for later stages.

## Quick Start

```bash
uv run fraudgen run --csv-path out/events.csv --seed 42 --duration-hours 1 --population-size 500
```

The same command with the same arguments produces byte-identical CSV output.
