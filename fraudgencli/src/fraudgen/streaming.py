from __future__ import annotations

import json
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol
from uuid import UUID, uuid5

from confluent_kafka import Producer

from fraudgen.models import TransactionEvent
from fraudgen.runner import RunArtifacts, build_run_artifacts
from fraudgen.run_config import RunConfig
from fraudgen.scenarios import FraudLabel


class KafkaProducerLike(Protocol):
    def produce(self, topic: str, *, key: str, value: str) -> None: ...

    def poll(self, timeout: float) -> int: ...

    def flush(self, timeout: float = 30.0) -> int: ...


def _producer_factory(config: dict[str, str]) -> KafkaProducerLike:
    return Producer(config)


@dataclass(frozen=True, slots=True)
class StreamSummary:
    cycles: int
    events: int
    labels: int


def stream_from_config(
    config: RunConfig,
    *,
    bootstrap_servers: str,
    topic: str,
    label_topic: str | None = None,
    loop: bool = False,
    delay_ms: int = 250,
    producer_factory: Callable[[dict[str, str]], KafkaProducerLike] = _producer_factory,
    now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    sleep: Callable[[float], None] = time.sleep,
    on_event: Callable[[str], None] | None = None,
) -> StreamSummary:
    artifacts = build_run_artifacts(config)
    producer = producer_factory({"bootstrap.servers": bootstrap_servers})
    cycle = 0
    published_events = 0
    published_labels = 0

    while True:
        summary = publish_artifacts(
            artifacts,
            producer=producer,
            topic=topic,
            label_topic=label_topic,
            cycle=cycle,
            base_time=now(),
            delay_ms=delay_ms,
            sleep=sleep,
            on_event=on_event,
        )
        published_events += summary.events
        published_labels += summary.labels
        cycle += 1
        if not loop:
            producer.flush(30.0)
            return StreamSummary(cycles=cycle, events=published_events, labels=published_labels)


def publish_artifacts(
    artifacts: RunArtifacts,
    *,
    producer: KafkaProducerLike,
    topic: str,
    label_topic: str | None,
    cycle: int,
    base_time: datetime,
    delay_ms: int,
    sleep: Callable[[float], None] = time.sleep,
    on_event: Callable[[str], None] | None = None,
) -> StreamSummary:
    if not artifacts.events:
        return StreamSummary(cycles=1, events=0, labels=0)

    first_timestamp = artifacts.events[0].timestamp
    labels_by_event_id = _labels_by_event_id(artifacts.labels)
    label_count = 0

    for event in artifacts.events:
        event_id = _cycle_uuid(event.event_id, cycle)
        timestamp = base_time + (event.timestamp - first_timestamp)
        producer.produce(
            topic,
            key=event_id,
            value=json.dumps(
                event_payload(event, event_id=event_id, timestamp=timestamp),
                sort_keys=True,
                separators=(",", ":"),
            ),
        )
        producer.poll(0.0)
        if on_event is not None:
            on_event(event_id)

        if label_topic is not None:
            for label in labels_by_event_id.get(str(event.event_id), []):
                producer.produce(
                    label_topic,
                    key=event_id,
                    value=json.dumps(
                        label_payload(label, event_id=event_id),
                        sort_keys=True,
                        separators=(",", ":"),
                    ),
                )
                label_count += 1
                producer.poll(0.0)

        if delay_ms > 0:
            sleep(delay_ms / 1000.0)

    producer.flush(30.0)
    return StreamSummary(cycles=1, events=len(artifacts.events), labels=label_count)


def event_payload(
    event: TransactionEvent,
    *,
    event_id: str,
    timestamp: datetime,
) -> dict[str, object]:
    payload = event.to_record()
    payload["event_id"] = event_id
    payload["timestamp"] = timestamp.astimezone(timezone.utc).isoformat()
    return payload


def label_payload(
    label: FraudLabel,
    *,
    event_id: str,
) -> dict[str, object]:
    payload = label.to_record()
    payload["event_id"] = event_id
    return payload


def _labels_by_event_id(labels: list[FraudLabel]) -> dict[str, list[FraudLabel]]:
    grouped: dict[str, list[FraudLabel]] = {}
    for label in labels:
        grouped.setdefault(str(label.event_id), []).append(label)
    return grouped


def _cycle_uuid(event_id: UUID, cycle: int) -> str:
    if cycle == 0:
        return str(event_id)
    return str(uuid5(event_id, f"cycle-{cycle}"))
