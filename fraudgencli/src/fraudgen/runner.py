from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import timedelta
from pathlib import Path
from typing import assert_never

import numpy as np
from numpy.random import Generator

from fraudgen.models import TransactionEvent
from fraudgen.population import Population
from fraudgen.run_config import (
    CardTestingScenarioConfig,
    NewAccountCashoutScenarioConfig,
    RunConfig,
    ScenarioConfig,
)
from fraudgen.scenarios import (
    CardTestingParams,
    CardTestingScenario,
    FraudLabel,
    NewAccountCashoutParams,
    NewAccountCashoutScenario,
    ScheduledEvent,
)
from fraudgen.simulator import BaselineSimulator, SimulationConfig, write_events_csv

LABEL_CSV_COLUMNS = [
    "event_id",
    "scenario_name",
    "scenario_instance_id",
    "role",
]


@dataclass(frozen=True, slots=True)
class RunSummary:
    total_events: int
    total_labels: int
    scenario_events: int


@dataclass(frozen=True, slots=True)
class RunArtifacts:
    events: list[TransactionEvent]
    labels: list[FraudLabel]
    scenario_events: int


def run_from_config(config: RunConfig) -> RunSummary:
    artifacts = build_run_artifacts(config)
    write_events_csv(artifacts.events, config.output.events_csv)
    write_labels_csv(artifacts.labels, config.output.labels_csv)
    return RunSummary(
        total_events=len(artifacts.events),
        total_labels=len(artifacts.labels),
        scenario_events=artifacts.scenario_events,
    )


def build_run_artifacts(config: RunConfig) -> RunArtifacts:
    rng = np.random.default_rng(config.seed)
    population = Population.create(config.population_size, rng, config.start_time)
    baseline_config = SimulationConfig(
        seed=config.seed,
        duration_hours=config.duration_hours,
        population_size=config.population_size,
        events_per_hour_target=config.events_per_hour_target,
        tick_seconds=config.tick_seconds,
        csv_path=config.output.events_csv,
        start_time=config.start_time,
    )
    baseline_events = BaselineSimulator(baseline_config, rng, population).run()
    scheduled = schedule_scenarios(config, population, rng)
    scenario_events = [item.event for item in scheduled]
    labels = [label for item in scheduled for label in item.labels]
    events = sorted(
        baseline_events + scenario_events,
        key=lambda event: (event.timestamp, str(event.event_id)),
    )

    return RunArtifacts(
        events=events,
        labels=labels,
        scenario_events=len(scenario_events),
    )


def schedule_scenarios(
    config: RunConfig,
    population: Population,
    rng: Generator,
) -> list[ScheduledEvent]:
    scheduled: list[ScheduledEvent] = []
    for scenario_config in config.scenarios:
        scenario = _build_scenario(scenario_config)
        for instance_index in range(scenario_config.count):
            sim_clock = config.start_time + timedelta(
                seconds=scenario_config.start_after_seconds
                + instance_index * scenario_config.spacing_seconds
            )
            scheduled.extend(scenario.schedule(sim_clock, population, rng))
    return scheduled


def write_labels_csv(labels: list[FraudLabel], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=LABEL_CSV_COLUMNS, lineterminator="\n")
        writer.writeheader()
        for label in labels:
            writer.writerow(label.to_record())


def _build_scenario(
    config: ScenarioConfig,
) -> NewAccountCashoutScenario | CardTestingScenario:
    if isinstance(config, NewAccountCashoutScenarioConfig):
        return NewAccountCashoutScenario(
            NewAccountCashoutParams(
                inbound_amount=config.inbound_amount,
                minutes_to_cashout=config.minutes_to_cashout,
                cashout_fraction=config.cashout_fraction,
            )
        )
    if isinstance(config, CardTestingScenarioConfig):
        return CardTestingScenario(
            CardTestingParams(
                attempt_count=config.attempt_count,
                window_seconds=config.window_seconds,
                amount_min=config.amount_min,
                amount_max=config.amount_max,
            )
        )
    assert_never(config)
