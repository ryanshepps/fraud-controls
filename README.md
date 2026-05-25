# fraudgen

Deterministic synthetic P2P traffic generator for a fraud controls platform.

Stage 1 includes the package skeleton, customer population model, archetype-based
baseline simulator, CSV output, and tests for deterministic output. Stage 2 adds
a scenario interface plus deterministic injectors for `new_account_cashout` and
`card_testing`. Stage 3 adds YAML-configured runs that produce separate event and
fraud-label CSV files. The controls platform now supports fraudgen event parsing,
deterministic transaction feature extraction, baseline risk scoring, typed rule
evaluation, scored decision orchestration, and pure payload-to-decision
processing. Kafka, Parquet, and full documentation are planned for later stages.

## Quick Start

```bash
uv run fraudgen run --csv-path out/events.csv --seed 42 --duration-hours 1 --population-size 500
```

The same command with the same arguments produces byte-identical CSV output.

Run the mixed baseline-plus-scenario generator from YAML:

```bash
uv run fraudgen run-config --config examples/stage3-run.yaml
```
