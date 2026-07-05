from __future__ import annotations

from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

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
            event_id=event_id or f"evt-{uuid4()}",
            event_type=event_type,
            occurred_at=recorded_at,
            causation_id=causation_id,
            correlation_id=correlation_id,
            schema_version=1,
            payload=payload or {},
        )
        self._publisher.publish(envelope)
