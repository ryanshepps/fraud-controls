from __future__ import annotations

import hashlib
from datetime import datetime, timedelta, timezone
from typing import TypeVar
from uuid import UUID

import numpy as np
from numpy.random import Generator

from fraudgen.models import Archetype, BehaviorParams, Customer, GeoPoint

T = TypeVar("T")


COUNTRY_WEIGHTS: tuple[tuple[str, float], ...] = (
    ("US", 0.86),
    ("CA", 0.04),
    ("GB", 0.03),
    ("MX", 0.025),
    ("AU", 0.015),
    ("DE", 0.015),
    ("FR", 0.015),
)

COUNTRY_CENTERS: dict[str, GeoPoint] = {
    "US": GeoPoint(39.8283, -98.5795),
    "CA": GeoPoint(56.1304, -106.3468),
    "GB": GeoPoint(55.3781, -3.4360),
    "MX": GeoPoint(23.6345, -102.5528),
    "AU": GeoPoint(-25.2744, 133.7751),
    "DE": GeoPoint(51.1657, 10.4515),
    "FR": GeoPoint(46.2276, 2.2137),
}

ARCHETYPE_WEIGHTS: tuple[tuple[Archetype, float], ...] = (
    (Archetype.REGULAR_PAYER, 0.34),
    (Archetype.BURSTY_BUSINESS, 0.08),
    (Archetype.DORMANT_USER, 0.22),
    (Archetype.SOCIAL_SPLITTER, 0.18),
    (Archetype.POWER_USER, 0.08),
    (Archetype.NEW_USER, 0.10),
)


def default_start_time() -> datetime:
    return datetime(2024, 1, 1, tzinfo=timezone.utc)


def deterministic_uuid(rng: Generator) -> UUID:
    return UUID(bytes=bytes(rng.bytes(16)))


def _weighted_choice(rng: Generator, weighted_values: tuple[tuple[T, float], ...]) -> T:
    values = [item[0] for item in weighted_values]
    probabilities = np.array([item[1] for item in weighted_values], dtype=float)
    probabilities = probabilities / probabilities.sum()
    return values[int(rng.choice(len(values), p=probabilities))]


def behavior_for_archetype(archetype: Archetype) -> BehaviorParams:
    business_hours = tuple(0.3 if hour < 8 or hour > 18 else 1.4 for hour in range(24))
    evenings = tuple(1.5 if 18 <= hour <= 23 else 0.6 for hour in range(24))
    broad = tuple(1.0 for _ in range(24))
    weekday = tuple(0.7 if hour < 7 or hour > 22 else 1.1 for hour in range(24))
    quiet = tuple(0.4 if 0 <= hour <= 7 else 0.8 for hour in range(24))

    return {
        Archetype.REGULAR_PAYER: BehaviorParams(1.0, 42.0, 0.65, 0.62, 0.65, weekday),
        Archetype.BURSTY_BUSINESS: BehaviorParams(1.8, 31.0, 0.75, 0.28, 0.45, business_hours),
        Archetype.DORMANT_USER: BehaviorParams(0.12, 24.0, 0.55, 0.35, 0.8, quiet),
        Archetype.SOCIAL_SPLITTER: BehaviorParams(1.45, 18.0, 0.45, 0.78, 1.35, evenings),
        Archetype.POWER_USER: BehaviorParams(3.2, 76.0, 0.9, 0.38, 1.0, broad),
        Archetype.NEW_USER: BehaviorParams(0.7, 22.0, 0.55, 0.18, 0.9, broad),
    }[archetype]


class Population:
    def __init__(self, customers: list[Customer]) -> None:
        self.customers = customers
        self.by_id = {customer.customer_id: customer for customer in customers}
        self._sender_cdf_cache: dict[tuple[bool, int], np.ndarray] = {}

    @classmethod
    def create(cls, size: int, rng: Generator, start_time: datetime | None = None) -> "Population":
        if size < 2:
            raise ValueError("population_size must be at least 2")

        start = start_time or default_start_time()
        customers = [_create_customer(rng, start) for _ in range(size)]
        return cls(customers)

    def sender_cdf_at(self, timestamp: datetime) -> np.ndarray:
        is_weekend = timestamp.weekday() >= 5
        hour = timestamp.hour
        cache_key = (is_weekend, hour)
        if cache_key in self._sender_cdf_cache:
            return self._sender_cdf_cache[cache_key]

        weights = []
        for customer in self.customers:
            params = customer.behavior_params
            weekend_factor = params.weekend_multiplier if is_weekend else 1.0
            weights.append(params.sender_weight * params.hourly_weights[hour] * weekend_factor)
        weights_array = np.array(weights, dtype=float)
        probabilities = weights_array / weights_array.sum()
        cdf = np.cumsum(probabilities)
        cdf[-1] = 1.0
        self._sender_cdf_cache[cache_key] = cdf
        return cdf

    def choose_sender(self, rng: Generator, timestamp: datetime) -> Customer:
        cdf = self.sender_cdf_at(timestamp)
        index = int(np.searchsorted(cdf, rng.random(), side="right"))
        return self.customers[index]

    def choose_recipient(self, sender: Customer, rng: Generator) -> Customer:
        if (
            sender.recent_counterparties
            and rng.random() < sender.behavior_params.repeat_counterparty_probability
        ):
            recipient_id = sender.recent_counterparties[
                int(rng.integers(0, len(sender.recent_counterparties)))
            ]
            if recipient_id != sender.customer_id and recipient_id in self.by_id:
                return self.by_id[recipient_id]

        while True:
            candidate = self.customers[int(rng.integers(0, len(self.customers)))]
            if candidate.customer_id != sender.customer_id:
                return candidate


def _create_customer(rng: Generator, start_time: datetime) -> Customer:
    archetype = _weighted_choice(rng, ARCHETYPE_WEIGHTS)
    country = _weighted_choice(rng, COUNTRY_WEIGHTS)
    params = behavior_for_archetype(archetype)
    age_days = float(
        rng.uniform(0.1, 30.0) if archetype is Archetype.NEW_USER else rng.uniform(31.0, 1460.0)
    )
    created_at = start_time - timedelta(days=age_days)
    center = COUNTRY_CENTERS[country]
    geo = GeoPoint(
        lat=float(center.lat + rng.normal(0.0, 2.5)),
        lng=float(center.lng + rng.normal(0.0, 2.5)),
    )
    balance = float(max(25.0, rng.lognormal(mean=6.0, sigma=1.0)))
    customer_id = deterministic_uuid(rng)
    fingerprint_seed = f"{customer_id}:{country}".encode()
    device_fingerprint = hashlib.sha256(fingerprint_seed).hexdigest()[:32]
    return Customer(
        customer_id=customer_id,
        created_at=created_at,
        country=country,
        archetype=archetype,
        device_fingerprint=device_fingerprint,
        home_geo=geo,
        balance=round(balance, 2),
        behavior_params=params,
    )
