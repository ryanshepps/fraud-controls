from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Annotated, Literal, Self

import yaml
from pydantic import BaseModel, ConfigDict, Field, model_validator

from fraudgen.population import default_start_time


class OutputConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    events_csv: Path
    labels_csv: Path


class NewAccountCashoutScenarioConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: Literal["new_account_cashout"]
    count: int = Field(default=1, ge=1)
    start_after_seconds: int = Field(default=0, ge=0)
    spacing_seconds: int = Field(default=300, ge=1)
    inbound_amount: float = Field(default=1500.0, gt=0)
    minutes_to_cashout: int = Field(default=5, ge=1)
    cashout_fraction: float = Field(default=0.85, gt=0, le=1)


class CardTestingScenarioConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: Literal["card_testing"]
    count: int = Field(default=1, ge=1)
    start_after_seconds: int = Field(default=0, ge=0)
    spacing_seconds: int = Field(default=300, ge=1)
    attempt_count: int = Field(default=12, ge=1)
    window_seconds: int = Field(default=60, ge=1)
    amount_min: float = Field(default=0.25, gt=0)
    amount_max: float = Field(default=2.0, gt=0)

    @model_validator(mode="after")
    def validate_amount_range(self) -> Self:
        if self.amount_min > self.amount_max:
            raise ValueError("amount_min must be less than or equal to amount_max")
        return self


ScenarioConfig = Annotated[
    NewAccountCashoutScenarioConfig | CardTestingScenarioConfig,
    Field(discriminator="name"),
]


class RunConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    seed: int = 42
    duration_hours: float = Field(default=1.0, ge=0)
    population_size: int = Field(default=500, ge=2)
    events_per_hour_target: float = Field(default=500.0, ge=0)
    tick_seconds: int = Field(default=60, ge=1)
    start_time: datetime = default_start_time()
    output: OutputConfig
    scenarios: tuple[ScenarioConfig, ...] = ()


def load_run_config(path: Path) -> RunConfig:
    with path.open(encoding="utf-8") as file:
        data: object = yaml.safe_load(file)
    return RunConfig.model_validate(data)
