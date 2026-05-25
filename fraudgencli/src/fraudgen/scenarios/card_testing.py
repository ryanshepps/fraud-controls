from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from numpy.random import Generator

from fraudgen.models import Archetype, Customer
from fraudgen.population import Population, create_customer, deterministic_uuid
from fraudgen.scenarios.base import FraudLabel, LabelRole, ScheduledEvent
from fraudgen.simulator import create_transaction_event


@dataclass(frozen=True, slots=True)
class CardTestingParams:
    attempt_count: int = 12
    window_seconds: int = 60
    amount_min: float = 0.25
    amount_max: float = 2.0


class CardTestingScenario:
    name = "card_testing"

    def __init__(self, params: CardTestingParams | None = None) -> None:
        self.params = params or CardTestingParams()

    def schedule(
        self,
        sim_clock: datetime,
        population: Population,
        rng: Generator,
    ) -> list[ScheduledEvent]:
        instance_id = deterministic_uuid(rng)
        sender = self._sender(sim_clock, population, rng)
        offsets = sorted(
            int(offset)
            for offset in rng.integers(
                0,
                self.params.window_seconds + 1,
                size=self.params.attempt_count,
            )
        )
        scheduled: list[ScheduledEvent] = []

        for offset in offsets:
            recipient = create_customer(
                rng,
                sim_clock,
                archetype=Archetype.NEW_USER,
                created_at=sim_clock - timedelta(days=float(rng.uniform(2.0, 20.0))),
                balance=5.0,
                country=sender.country,
            )
            population.add_customer(recipient)
            event = create_transaction_event(
                event_id=deterministic_uuid(rng),
                timestamp=sim_clock + timedelta(seconds=offset),
                sender=sender,
                recipient=recipient,
                amount=float(rng.uniform(self.params.amount_min, self.params.amount_max)),
            )
            scheduled.append(
                ScheduledEvent(
                    event=event,
                    labels=(
                        FraudLabel(
                            event_id=event.event_id,
                            scenario_name=self.name,
                            scenario_instance_id=instance_id,
                            role=LabelRole.SENDER,
                        ),
                    ),
                )
            )

        return scheduled

    def _sender(self, sim_clock: datetime, population: Population, rng: Generator) -> Customer:
        minimum_balance = self.params.attempt_count * self.params.amount_max + 10.0
        candidates = [
            customer
            for customer in population.customers
            if customer.balance >= minimum_balance
            and (sim_clock - customer.created_at) >= timedelta(days=30)
        ]
        if candidates:
            return candidates[int(rng.integers(0, len(candidates)))]

        sender = create_customer(
            rng,
            sim_clock,
            archetype=Archetype.POWER_USER,
            created_at=sim_clock - timedelta(days=365),
            balance=minimum_balance,
        )
        population.add_customer(sender)
        return sender
