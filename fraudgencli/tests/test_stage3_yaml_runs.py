from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd
import pytest
from pydantic import ValidationError

from fraudgen.cli import main
from fraudgen.run_config import load_run_config
from fraudgen.runner import LABEL_CSV_COLUMNS, build_run_artifacts, run_from_config
from fraudgen.simulator import CSV_COLUMNS
from fraudgen.streaming import publish_artifacts


def test_yaml_run_writes_events_and_labels_csv(tmp_path: Path) -> None:
    config_path = _write_config(tmp_path, "events.csv", "labels.csv")

    summary = run_from_config(load_run_config(config_path))

    events = pd.read_csv(tmp_path / "events.csv")
    labels = pd.read_csv(tmp_path / "labels.csv")
    assert summary.scenario_events == 14
    assert summary.total_labels == 14
    assert summary.total_events == len(events)
    assert list(events.columns) == CSV_COLUMNS
    assert list(labels.columns) == LABEL_CSV_COLUMNS
    assert "is_fraud" not in events.columns
    assert set(labels["scenario_name"]) == {"new_account_cashout", "card_testing"}
    assert set(labels["event_id"]).issubset(set(events["event_id"]))


def test_yaml_run_is_byte_deterministic(tmp_path: Path) -> None:
    first_path = _write_config(tmp_path, "first_events.csv", "first_labels.csv")
    second_path = _write_config(tmp_path, "second_events.csv", "second_labels.csv")

    first_summary = run_from_config(load_run_config(first_path))
    second_summary = run_from_config(load_run_config(second_path))

    assert first_summary == second_summary
    assert (tmp_path / "first_events.csv").read_bytes() == (
        tmp_path / "second_events.csv"
    ).read_bytes()
    assert (tmp_path / "first_labels.csv").read_bytes() == (
        tmp_path / "second_labels.csv"
    ).read_bytes()


def test_cli_run_config_writes_outputs(tmp_path: Path) -> None:
    config_path = _write_config(tmp_path, "events.csv", "labels.csv")

    exit_code = main(["run-config", "--config", str(config_path)])

    assert exit_code == 0
    assert (tmp_path / "events.csv").exists()
    assert (tmp_path / "labels.csv").exists()


def test_streaming_publishes_rebased_json_events_and_labels(tmp_path: Path) -> None:
    config_path = _write_config(tmp_path, "events.csv", "labels.csv")
    artifacts = build_run_artifacts(load_run_config(config_path))
    producer = RecordingProducer()

    summary = publish_artifacts(
        artifacts,
        producer=producer,
        topic="transactions",
        label_topic="fraud_labels",
        cycle=0,
        base_time=datetime(2026, 5, 26, 19, 0, tzinfo=timezone.utc),
        delay_ms=0,
    )

    event_records = [record for record in producer.records if record[0] == "transactions"]
    label_records = [record for record in producer.records if record[0] == "fraud_labels"]
    first_payload = json.loads(event_records[0][2])
    assert summary.events == len(artifacts.events)
    assert summary.labels == len(artifacts.labels)
    assert len(event_records) == len(artifacts.events)
    assert len(label_records) == len(artifacts.labels)
    assert first_payload["event_id"] == str(artifacts.events[0].event_id)
    assert first_payload["timestamp"] == "2026-05-26T19:00:00+00:00"
    assert set(first_payload) == set(CSV_COLUMNS)
    assert {json.loads(record[2])["event_id"] for record in label_records}.issubset(
        {json.loads(record[2])["event_id"] for record in event_records}
    )


def test_yaml_run_rejects_unknown_scenario(tmp_path: Path) -> None:
    config_path = tmp_path / "run.yaml"
    config_path.write_text(
        f"""
output:
  events_csv: {tmp_path / "events.csv"}
  labels_csv: {tmp_path / "labels.csv"}
scenarios:
  - name: unknown
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(ValidationError, match="union_tag_invalid"):
        load_run_config(config_path)


def test_yaml_run_rejects_invalid_amount_range(tmp_path: Path) -> None:
    config_path = tmp_path / "run.yaml"
    config_path.write_text(
        f"""
output:
  events_csv: {tmp_path / "events.csv"}
  labels_csv: {tmp_path / "labels.csv"}
scenarios:
  - name: card_testing
    amount_min: 5.0
    amount_max: 1.0
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(ValidationError, match="amount_min"):
        load_run_config(config_path)


def test_yaml_run_rejects_population_too_small(tmp_path: Path) -> None:
    config_path = tmp_path / "run.yaml"
    config_path.write_text(
        f"""
population_size: 1
output:
  events_csv: {tmp_path / "events.csv"}
  labels_csv: {tmp_path / "labels.csv"}
""".strip(),
        encoding="utf-8",
    )

    with pytest.raises(ValidationError, match="population_size"):
        load_run_config(config_path)


def _write_config(tmp_path: Path, events_name: str, labels_name: str) -> Path:
    config_path = tmp_path / f"{events_name}.yaml"
    config_path.write_text(
        f"""
seed: 99
duration_hours: 0.1
population_size: 30
events_per_hour_target: 60.0
tick_seconds: 60
start_time: "2024-02-01T12:00:00+00:00"
output:
  events_csv: {tmp_path / events_name}
  labels_csv: {tmp_path / labels_name}
scenarios:
  - name: new_account_cashout
    count: 1
    start_after_seconds: 120
    inbound_amount: 1200.0
    minutes_to_cashout: 4
    cashout_fraction: 0.9
  - name: card_testing
    count: 1
    start_after_seconds: 240
    attempt_count: 12
    window_seconds: 60
    amount_min: 0.25
    amount_max: 1.50
""".strip(),
        encoding="utf-8",
    )
    return config_path


class RecordingProducer:
    def __init__(self) -> None:
        self.records: list[tuple[str, str, str]] = []

    def produce(self, topic: str, *, key: str, value: str) -> None:
        self.records.append((topic, key, value))

    def poll(self, timeout: float) -> int:
        return 0

    def flush(self, timeout: float = 30.0) -> int:
        return 0
