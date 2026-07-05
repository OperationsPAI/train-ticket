from __future__ import annotations

from datetime import UTC, datetime
from typing import Any, Mapping

from train_ticket_platform.events import EventEnvelope as _PlatformEventEnvelope, MalformedEnvelopeError


class EventEnvelope(_PlatformEventEnvelope):
    def __init__(
        self,
        event_id: str | None = None,
        event_type: str | None = None,
        occurred_at: datetime | str | None = None,
        correlation_id: str = "",
        causation_id: str | None = "",
        producer: str = "fare-pricing",
        schema_version: int = 1,
        payload: Mapping[str, Any] | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(
            eventId=event_id or kwargs.pop("eventId", "evt-test"),
            eventType=event_type or kwargs.pop("eventType", ""),
            occurredAt=occurred_at or kwargs.pop("occurredAt", None) or datetime.now(UTC),
            correlationId=correlation_id or kwargs.pop("correlationId", ""),
            causationId=causation_id if causation_id not in (None, "") else kwargs.pop("causationId", None),
            producer=producer,
            schemaVersion=schema_version,
            payload=payload or kwargs.pop("payload", {}),
        )

    @classmethod
    def from_json_dict(cls, data: Mapping[str, Any]) -> "EventEnvelope":
        restored = _PlatformEventEnvelope.from_json_dict(data)
        return cls(
            event_id=restored.eventId,
            event_type=restored.eventType,
            occurred_at=restored.occurredAt,
            correlation_id=restored.correlationId,
            causation_id=restored.causationId or "",
            producer=restored.producer,
            schema_version=restored.schemaVersion,
            payload=restored.payload,
        )


__all__ = ["EventEnvelope", "MalformedEnvelopeError"]
