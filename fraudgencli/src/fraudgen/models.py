from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from uuid import UUID


class Archetype(StrEnum):
    REGULAR_PAYER = "regular_payer"
    BURSTY_BUSINESS = "bursty_business"
    DORMANT_USER = "dormant_user"
    SOCIAL_SPLITTER = "social_splitter"
    POWER_USER = "power_user"
    NEW_USER = "new_user"


@dataclass(frozen=True, slots=True)
class GeoPoint:
    lat: float
    lng: float


@dataclass(frozen=True, slots=True)
class BehaviorParams:
    sender_weight: float
    median_amount: float
    amount_sigma: float
    repeat_counterparty_probability: float
    weekend_multiplier: float
    hourly_weights: tuple[float, ...]


@dataclass(slots=True)
class Customer:
    customer_id: UUID
    created_at: datetime
    country: str
    archetype: Archetype
    device_fingerprint: str
    home_geo: GeoPoint
    balance: float
    behavior_params: BehaviorParams
    recent_counterparties: deque[UUID] = field(default_factory=lambda: deque(maxlen=20))


@dataclass(frozen=True, slots=True)
class TransactionEvent:
    event_id: UUID
    timestamp: datetime
    sender_id: UUID
    recipient_id: UUID
    amount: float
    currency: str
    type: str
    sender_device_fingerprint: str
    sender_geo: GeoPoint
    sender_balance_before: float
    sender_balance_after: float
    recipient_balance_before: float
    recipient_balance_after: float
    sender_account_age_days: float
    recipient_account_age_days: float
    is_new_counterparty: bool

    def to_record(self) -> dict[str, object]:
        return {
            "event_id": str(self.event_id),
            "timestamp": self.timestamp.isoformat(),
            "sender_id": str(self.sender_id),
            "recipient_id": str(self.recipient_id),
            "amount": round(self.amount, 2),
            "currency": self.currency,
            "type": self.type,
            "sender_device_fingerprint": self.sender_device_fingerprint,
            "sender_geo": {
                "lat": round(self.sender_geo.lat, 6),
                "lng": round(self.sender_geo.lng, 6),
            },
            "sender_balance_before": round(self.sender_balance_before, 2),
            "sender_balance_after": round(self.sender_balance_after, 2),
            "recipient_balance_before": round(self.recipient_balance_before, 2),
            "recipient_balance_after": round(self.recipient_balance_after, 2),
            "sender_account_age_days": round(self.sender_account_age_days, 3),
            "recipient_account_age_days": round(self.recipient_account_age_days, 3),
            "is_new_counterparty": self.is_new_counterparty,
        }
