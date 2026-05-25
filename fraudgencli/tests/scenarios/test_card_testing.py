from __future__ import annotations

from datetime import datetime, timezone

import numpy as np

from fraudgen.population import Population
from fraudgen.scenarios import CardTestingScenario, LabelRole


def test_card_testing_creates_many_small_labeled_attempts() -> None:
    sim_clock = datetime(2024, 2, 1, 12, tzinfo=timezone.utc)
    rng = np.random.default_rng(303)
    population = Population.create(20, rng, sim_clock)

    scheduled = CardTestingScenario().schedule(sim_clock, population, rng)

    assert len(scheduled) == 12
    events = [item.event for item in scheduled]
    sender_ids = {event.sender_id for event in events}
    recipient_ids = {event.recipient_id for event in events}
    timestamps = [event.timestamp for event in events]
    labels = [item.labels[0] for item in scheduled]

    assert len(sender_ids) == 1
    assert len(recipient_ids) == len(events)
    assert min(timestamps) >= sim_clock
    assert (max(timestamps) - sim_clock).total_seconds() <= 60
    assert timestamps == sorted(timestamps)
    assert all(0.25 <= event.amount <= 2.0 for event in events)
    assert all(event.is_new_counterparty for event in events)
    assert {label.scenario_name for label in labels} == {"card_testing"}
    assert {label.scenario_instance_id for label in labels} == {labels[0].scenario_instance_id}
    assert {label.role for label in labels} == {LabelRole.SENDER}
    assert [label.event_id for label in labels] == [event.event_id for event in events]


def test_card_testing_is_deterministic() -> None:
    sim_clock = datetime(2024, 2, 1, 12, tzinfo=timezone.utc)

    first_rng = np.random.default_rng(404)
    first_population = Population.create(20, first_rng, sim_clock)
    first = CardTestingScenario().schedule(sim_clock, first_population, first_rng)

    second_rng = np.random.default_rng(404)
    second_population = Population.create(20, second_rng, sim_clock)
    second = CardTestingScenario().schedule(sim_clock, second_population, second_rng)

    assert [item.event.to_record() for item in first] == [item.event.to_record() for item in second]
    assert [[label.to_record() for label in item.labels] for item in first] == [
        [label.to_record() for label in item.labels] for item in second
    ]
