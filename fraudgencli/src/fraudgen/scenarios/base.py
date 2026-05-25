from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum
from typing import Protocol
from uuid import UUID

from numpy.random import Generator

from fraudgen.models import TransactionEvent
from fraudgen.population import Population


class LabelRole(StrEnum):
    SENDER = "sender"
    RECIPIENT = "recipient"
    INVOLVED = "involved"


@dataclass(frozen=True, slots=True)
class FraudLabel:
    event_id: UUID
    scenario_name: str
    scenario_instance_id: UUID
    role: LabelRole

    def to_record(self) -> dict[str, object]:
        return {
            "event_id": str(self.event_id),
            "scenario_name": self.scenario_name,
            "scenario_instance_id": str(self.scenario_instance_id),
            "role": self.role.value,
        }


@dataclass(frozen=True, slots=True)
class ScheduledEvent:
    event: TransactionEvent
    labels: tuple[FraudLabel, ...]


class Scenario(Protocol):
    name: str

    def schedule(
        self,
        sim_clock: datetime,
        population: Population,
        rng: Generator,
    ) -> list[ScheduledEvent]:
        """Inject events at sim_clock using the caller-provided deterministic RNG."""
