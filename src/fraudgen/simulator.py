from __future__ import annotations

import csv
import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
from numpy.random import Generator

from fraudgen.models import Customer, TransactionEvent
from fraudgen.population import Population, default_start_time, deterministic_uuid


CSV_COLUMNS = [
    "event_id",
    "timestamp",
    "sender_id",
    "recipient_id",
    "amount",
    "currency",
    "type",
    "sender_device_fingerprint",
    "sender_geo",
    "sender_balance_before",
    "sender_balance_after",
    "recipient_balance_before",
    "recipient_balance_after",
    "sender_account_age_days",
    "recipient_account_age_days",
    "is_new_counterparty",
]

MONEY_COLUMNS = {
    "amount",
    "sender_balance_before",
    "sender_balance_after",
    "recipient_balance_before",
    "recipient_balance_after",
}


@dataclass(frozen=True, slots=True)
class SimulationConfig:
    seed: int = 42
    duration_hours: float = 1.0
    population_size: int = 500
    events_per_hour_target: float = 500.0
    tick_seconds: int = 60
    csv_path: Path = Path("out/events.csv")
    start_time: datetime = default_start_time()


@dataclass(frozen=True, slots=True)
class SimulationSummary:
    total_events: int
    active_customers: int
    average_events_per_active_customer: float


class BaselineSimulator:
    def __init__(self, config: SimulationConfig, rng: Generator, population: Population) -> None:
        self.config = config
        self.rng = rng
        self.population = population

    @classmethod
    def create(cls, config: SimulationConfig) -> "BaselineSimulator":
        rng = np.random.default_rng(config.seed)
        population = Population.create(config.population_size, rng, config.start_time)
        return cls(config=config, rng=rng, population=population)

    def run(self) -> list[TransactionEvent]:
        events: list[TransactionEvent] = []
        tick_count = int((self.config.duration_hours * 3600) // self.config.tick_seconds)
        expected_per_tick = self.config.events_per_hour_target * self.config.tick_seconds / 3600

        for tick_index in range(tick_count):
            tick_time = self.config.start_time + timedelta(
                seconds=tick_index * self.config.tick_seconds
            )
            event_count = int(self.rng.poisson(expected_per_tick))
            for offset in self._event_offsets(event_count):
                event_time = tick_time + timedelta(seconds=int(offset))
                event = self._create_baseline_event(event_time)
                if event is not None:
                    events.append(event)

        return events

    def _event_offsets(self, event_count: int) -> list[int]:
        if event_count <= 0:
            return []
        offsets = [
            int(value) for value in self.rng.integers(0, self.config.tick_seconds, size=event_count)
        ]
        return sorted(offsets)

    def _create_baseline_event(self, timestamp: datetime) -> TransactionEvent | None:
        sender = self.population.choose_sender(self.rng, timestamp)
        if sender.balance < 1.0:
            return None

        recipient = self.population.choose_recipient(sender, self.rng)
        amount = self._sample_amount(sender)
        if amount <= 0:
            return None

        sender_balance_before = sender.balance
        recipient_balance_before = recipient.balance
        sender.balance = round(sender.balance - amount, 2)
        recipient.balance = round(recipient.balance + amount, 2)
        is_new_counterparty = recipient.customer_id not in sender.recent_counterparties
        sender.recent_counterparties.append(recipient.customer_id)

        return TransactionEvent(
            event_id=deterministic_uuid(self.rng),
            timestamp=timestamp,
            sender_id=sender.customer_id,
            recipient_id=recipient.customer_id,
            amount=amount,
            currency="USD",
            type="p2p_send",
            sender_device_fingerprint=sender.device_fingerprint,
            sender_geo=sender.home_geo,
            sender_balance_before=sender_balance_before,
            sender_balance_after=sender.balance,
            recipient_balance_before=recipient_balance_before,
            recipient_balance_after=recipient.balance,
            sender_account_age_days=(timestamp - sender.created_at).total_seconds() / 86400,
            recipient_account_age_days=(timestamp - recipient.created_at).total_seconds() / 86400,
            is_new_counterparty=is_new_counterparty,
        )

    def _sample_amount(self, sender: Customer) -> float:
        params = sender.behavior_params
        amount = float(
            self.rng.lognormal(mean=np.log(params.median_amount), sigma=params.amount_sigma)
        )
        balance_cap = max(0.0, sender.balance * 0.45)
        return round(min(amount, balance_cap), 2)


def write_events_csv(events: list[TransactionEvent], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_COLUMNS, lineterminator="\n")
        writer.writeheader()
        for event in events:
            record = event.to_record()
            record["sender_geo"] = json.dumps(
                record["sender_geo"], sort_keys=True, separators=(",", ":")
            )
            for column in MONEY_COLUMNS:
                record[column] = f"{record[column]:.2f}"
            writer.writerow(record)


def summarize(events: list[TransactionEvent]) -> SimulationSummary:
    active_customers = {event.sender_id for event in events} | {
        event.recipient_id for event in events
    }
    total_events = len(events)
    average = total_events / len(active_customers) if active_customers else 0.0
    return SimulationSummary(
        total_events=total_events,
        active_customers=len(active_customers),
        average_events_per_active_customer=round(average, 3),
    )


def run_to_csv(config: SimulationConfig) -> SimulationSummary:
    simulator = BaselineSimulator.create(config)
    events = simulator.run()
    write_events_csv(events, config.csv_path)
    return summarize(events)
