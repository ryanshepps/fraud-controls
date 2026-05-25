from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from numpy.random import Generator

from fraudgen.models import Archetype, Customer
from fraudgen.population import Population, create_customer, deterministic_uuid
from fraudgen.scenarios.base import FraudLabel, LabelRole, ScheduledEvent
from fraudgen.simulator import create_transaction_event


@dataclass(frozen=True, slots=True)
class NewAccountCashoutParams:
    inbound_amount: float = 1500.0
    minutes_to_cashout: int = 5
    cashout_fraction: float = 0.85


class NewAccountCashoutScenario:
    name = "new_account_cashout"

    def __init__(self, params: NewAccountCashoutParams | None = None) -> None:
        self.params = params or NewAccountCashoutParams()

    def schedule(
        self,
        sim_clock: datetime,
        population: Population,
        rng: Generator,
    ) -> list[ScheduledEvent]:
        instance_id = deterministic_uuid(rng)
        source = self._funding_source(sim_clock, population, rng)
        new_account = create_customer(
            rng,
            sim_clock,
            archetype=Archetype.NEW_USER,
            created_at=sim_clock - timedelta(hours=float(rng.uniform(1.0, 20.0))),
            balance=25.0,
            country=source.country,
        )
        cashout_recipient = create_customer(
            rng,
            sim_clock,
            archetype=Archetype.NEW_USER,
            created_at=sim_clock - timedelta(minutes=float(rng.uniform(5.0, 90.0))),
            balance=10.0,
            country=source.country,
        )
        population.add_customer(new_account)
        population.add_customer(cashout_recipient)

        inbound = create_transaction_event(
            event_id=deterministic_uuid(rng),
            timestamp=sim_clock,
            sender=source,
            recipient=new_account,
            amount=self.params.inbound_amount,
        )
        cashout = create_transaction_event(
            event_id=deterministic_uuid(rng),
            timestamp=sim_clock + timedelta(minutes=self.params.minutes_to_cashout),
            sender=new_account,
            recipient=cashout_recipient,
            amount=new_account.balance * self.params.cashout_fraction,
        )

        return [
            ScheduledEvent(
                event=inbound,
                labels=(
                    FraudLabel(
                        event_id=inbound.event_id,
                        scenario_name=self.name,
                        scenario_instance_id=instance_id,
                        role=LabelRole.RECIPIENT,
                    ),
                ),
            ),
            ScheduledEvent(
                event=cashout,
                labels=(
                    FraudLabel(
                        event_id=cashout.event_id,
                        scenario_name=self.name,
                        scenario_instance_id=instance_id,
                        role=LabelRole.SENDER,
                    ),
                ),
            ),
        ]

    def _funding_source(
        self,
        sim_clock: datetime,
        population: Population,
        rng: Generator,
    ) -> Customer:
        candidates = [
            customer
            for customer in population.customers
            if customer.balance >= self.params.inbound_amount
            and (sim_clock - customer.created_at) >= timedelta(days=30)
        ]
        if candidates:
            return candidates[int(rng.integers(0, len(candidates)))]

        source = create_customer(
            rng,
            sim_clock,
            archetype=Archetype.POWER_USER,
            created_at=sim_clock - timedelta(days=365),
            balance=self.params.inbound_amount + 500.0,
        )
        population.add_customer(source)
        return source
