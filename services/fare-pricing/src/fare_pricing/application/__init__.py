from __future__ import annotations

from datetime import datetime
from typing import Any

from ..ports import EventEnvelope
from ..ports.messaging import EventPublisher


class DomainEventService:
    """Application service that publishes domain events via the EventPublisher port."""

    def __init__(self, publisher: EventPublisher) -> None:
        self._publisher = publisher

    def publish_event(
        self,
        event_id: str,
        event_type: str,
        occurred_at: datetime,
        source_command_id: str,
        causation_id: str,
        correlation_id: str,
        payload: dict[str, Any] | None = None,
    ) -> None:
        envelope = EventEnvelope(
            event_id=event_id,
            event_type=event_type,
            occurred_at=occurred_at,
            source_command_id=source_command_id,
            causation_id=causation_id,
            correlation_id=correlation_id,
            schema_version=1,
            payload=payload or {},
        )
        self._publisher.publish(envelope)
