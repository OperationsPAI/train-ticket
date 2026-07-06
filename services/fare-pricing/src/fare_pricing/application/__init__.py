from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from ..ids import prefixed_uuid7
from ..ports import EventEnvelope
from ..ports.messaging import EventPublisher


class DomainEventService:
    """Application service that publishes domain events via the EventPublisher port."""

    def __init__(self, publisher: EventPublisher) -> None:
        self._publisher = publisher

    def publish_event(
        self,
        event_type: str,
        causation_id: str,
        correlation_id: str,
        payload: dict[str, Any] | None = None,
        occurred_at: datetime | None = None,
        event_id: str | None = None,
    ) -> None:
        recorded_at = occurred_at or datetime.now(UTC)
        envelope = EventEnvelope(
            event_id=event_id or prefixed_uuid7("evt"),
            event_type=event_type,
            occurred_at=recorded_at,
            causation_id=causation_id,
            correlation_id=correlation_id,
            schema_version=1,
            payload=payload or {},
        )
        self._publisher.publish(envelope)


def deterministic_uuid(value: str) -> str:
    uuid = uuid5(NAMESPACE_URL, f"train-ticket:fare-pricing:{value}")
    uuid_int = (uuid.int & ~(0xF << 76)) | (0x7 << 76)
    return str(uuid.__class__(int=uuid_int))


def deterministic_prefixed_id(prefix: str, *parts: str) -> str:
    return f"{prefix}-{deterministic_uuid(':'.join(parts))}"


def deterministic_rule_set_event_id(rule_set_id: str, version: str, action: str) -> str:
    return deterministic_prefixed_id("evt", rule_set_id, version, action)
