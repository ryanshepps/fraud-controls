from __future__ import annotations

from datetime import datetime, timezone

import numpy as np

from fraudgen.population import Population
from fraudgen.scenarios import LabelRole, NewAccountCashoutScenario


def test_new_account_cashout_creates_labeled_two_step_pattern() -> None:
    sim_clock = datetime(2024, 2, 1, 12, tzinfo=timezone.utc)
    rng = np.random.default_rng(101)
    population = Population.create(20, rng, sim_clock)

    scheduled = NewAccountCashoutScenario().schedule(sim_clock, population, rng)

    assert len(scheduled) == 2
    inbound = scheduled[0].event
    cashout = scheduled[1].event
    assert inbound.timestamp == sim_clock
    assert cashout.timestamp > inbound.timestamp
    assert cashout.sender_id == inbound.recipient_id
    assert inbound.recipient_account_age_days < 1
    assert cashout.recipient_account_age_days < 1
    assert cashout.amount > cashout.sender_balance_before * 0.8
    assert cashout.sender_balance_after == round(cashout.sender_balance_before - cashout.amount, 2)

    labels = [scheduled_event.labels[0] for scheduled_event in scheduled]
    assert {label.scenario_name for label in labels} == {"new_account_cashout"}
    assert {label.scenario_instance_id for label in labels} == {labels[0].scenario_instance_id}
    assert labels[0].event_id == inbound.event_id
    assert labels[0].role is LabelRole.RECIPIENT
    assert labels[1].event_id == cashout.event_id
    assert labels[1].role is LabelRole.SENDER


def test_new_account_cashout_is_deterministic() -> None:
    sim_clock = datetime(2024, 2, 1, 12, tzinfo=timezone.utc)

    first_rng = np.random.default_rng(202)
    first_population = Population.create(20, first_rng, sim_clock)
    first = NewAccountCashoutScenario().schedule(sim_clock, first_population, first_rng)

    second_rng = np.random.default_rng(202)
    second_population = Population.create(20, second_rng, sim_clock)
    second = NewAccountCashoutScenario().schedule(sim_clock, second_population, second_rng)

    assert [item.event.to_record() for item in first] == [item.event.to_record() for item in second]
    assert [[label.to_record() for label in item.labels] for item in first] == [
        [label.to_record() for label in item.labels] for item in second
    ]
