from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Mapping
from uuid import uuid4


def _new_event_id() -> str:
    return f"evt-{uuid4()}"


def _format_occurred_at(dt: datetime) -> str:
    """Format a datetime as an RFC3339 UTC timestamp with milliseconds."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


@dataclass(frozen=True)
class EventEnvelope:
    """Event bus wire envelope from shared-primitives.md §1."""

    eventId: str
    eventType: str
    schemaVersion: int = 1
    producer: str = "trip-planning"
    causationId: str = ""
    correlationId: str = ""
    occurredAt: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    payload: Mapping[str, Any] = field(default_factory=dict)

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "eventId": self.eventId,
            "eventType": self.eventType,
            "schemaVersion": self.schemaVersion,
            "producer": self.producer,
            "causationId": self.causationId,
            "correlationId": self.correlationId,
            "occurredAt": _format_occurred_at(self.occurredAt),
            "payload": dict(self.payload),
        }

    @classmethod
    def from_json_dict(cls, data: Mapping[str, Any]) -> "EventEnvelope":
        occurred_at = data.get("occurredAt", "")
        if isinstance(occurred_at, str):
            occurred_at = datetime.fromisoformat(occurred_at.replace("Z", "+00:00"))
        return cls(
            eventId=str(data["eventId"]),
            eventType=str(data["eventType"]),
            schemaVersion=int(data.get("schemaVersion", 1)),
            producer=str(data.get("producer", "trip-planning")),
            causationId=str(data.get("causationId", "")),
            correlationId=str(data.get("correlationId", "")),
            occurredAt=occurred_at,
            payload=data.get("payload", {}),
        )


class PublishFailed(Exception):
    """The event was not published."""


class SubscribeFailed(Exception):
    """The subscriber could not start."""


class HandlerError(Exception):
    """Raised by event handlers to indicate transient or fatal errors."""


class TransientHandlerError(HandlerError):
    """Transient error: message should be retried without XACK."""


class FatalHandlerError(HandlerError):
    """Fatal error: message should be moved to DLQ."""


def build_itinerary_proposed_event(
    intent_ref: str,
    itineraries: tuple[dict[str, Any], ...],
    planning_snapshot_refs: tuple[str, ...],
    *,
    correlation_id: str = "",
    causation_id: str = "",
) -> EventEnvelope:
    """Build an ItineraryProposed domain event envelope."""
    payload = {
        "intentRef": intent_ref,
        "itineraries": [
            {
                "itineraryRef": itinerary.get("itineraryRef"),
                "legs": itinerary.get("legs", []),
                "priceHint": itinerary.get("priceHint"),
                "availabilityHint": itinerary.get("availabilityHint"),
            }
            for itinerary in itineraries
        ],
        "planningSnapshotRefs": list(planning_snapshot_refs),
    }
    return EventEnvelope(
        eventId=_new_event_id(),
        eventType="ItineraryProposed",
        producer="trip-planning",
        causationId=causation_id,
        correlationId=correlation_id,
        payload=payload,
    )
