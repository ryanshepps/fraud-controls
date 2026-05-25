from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import pandas as pd
import pytest

from fraudgen.cli import main
from fraudgen.population import Population
from fraudgen.simulator import CSV_COLUMNS, BaselineSimulator, SimulationConfig, run_to_csv


def test_population_generation_is_deterministic() -> None:
    config = SimulationConfig(seed=7, population_size=10)

    first = BaselineSimulator.create(config).population.customers
    second = BaselineSimulator.create(config).population.customers

    assert [customer.customer_id for customer in first] == [
        customer.customer_id for customer in second
    ]
    assert [customer.archetype for customer in first] == [customer.archetype for customer in second]
    assert [customer.device_fingerprint for customer in first] == [
        customer.device_fingerprint for customer in second
    ]


def test_population_requires_at_least_two_customers() -> None:
    config = SimulationConfig(seed=1, population_size=2)
    rng = BaselineSimulator.create(config).rng

    with pytest.raises(ValueError, match="population_size"):
        Population.create(1, rng)


def test_baseline_events_have_expected_shape() -> None:
    config = SimulationConfig(
        seed=9, population_size=50, duration_hours=0.25, events_per_hour_target=120
    )
    events = BaselineSimulator.create(config).run()

    assert events
    for event in events:
        record = event.to_record()
        assert list(record.keys()) == CSV_COLUMNS
        assert "is_fraud" not in record
        assert isinstance(record["amount"], float)
        assert isinstance(record["sender_balance_before"], float)
        assert event.sender_id != event.recipient_id
        assert event.amount > 0
        assert event.sender_balance_after >= 0
        assert event.sender_balance_after == round(event.sender_balance_before - event.amount, 2)
        assert event.recipient_balance_after == round(
            event.recipient_balance_before + event.amount, 2
        )


def test_run_to_csv_is_byte_deterministic(tmp_path: Path) -> None:
    first_path = tmp_path / "first.csv"
    second_path = tmp_path / "second.csv"
    config = SimulationConfig(seed=42, population_size=100, duration_hours=1, csv_path=first_path)

    first_summary = run_to_csv(config)
    second_summary = run_to_csv(replace(config, csv_path=second_path))

    assert first_summary == second_summary
    assert first_path.read_bytes() == second_path.read_bytes()


def test_zero_duration_run_writes_header_only_csv(tmp_path: Path) -> None:
    csv_path = tmp_path / "empty.csv"

    summary = run_to_csv(SimulationConfig(seed=11, duration_hours=0, csv_path=csv_path))

    assert summary.total_events == 0
    assert csv_path.read_text(encoding="utf-8") == ",".join(CSV_COLUMNS) + "\n"


def test_cli_run_writes_csv(tmp_path: Path) -> None:
    csv_path = tmp_path / "events.csv"

    exit_code = main(
        [
            "run",
            "--csv-path",
            str(csv_path),
            "--seed",
            "123",
            "--duration-hours",
            "0.1",
            "--population-size",
            "25",
            "--events-per-hour-target",
            "60",
        ]
    )

    assert exit_code == 0
    frame = pd.read_csv(csv_path)
    assert list(frame.columns) == CSV_COLUMNS
