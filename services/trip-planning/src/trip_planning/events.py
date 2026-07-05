from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Mapping
from uuid import uuid4


def _new_event_id() -> str:
    return f"evt-{uuid4().hex}"


def _format_occurred_at(dt: datetime) -> str:
    """Format a datetime as RFC3339 UTC string with milliseconds."""
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


@dataclass(frozen=True)
class EventEnvelope:
    """Wire format for every domain event per shared-primitives.md §1."""

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
    def from_json_dict(cls, data: Mapping[str, Any]) -> EventEnvelope:
        occurred_at = data.get("occurredAt", "")
        if isinstance(occurred_at, str):
            occurred_at = datetime.fromisoformat(occurred_at.replace("Z", "+00:00"))
        return cls(
            eventId=data["eventId"],
            eventType=data["eventType"],
            schemaVersion=int(data.get("schemaVersion", 1)),
            producer=data.get("producer", "trip-planning"),
            causationId=data.get("causationId", ""),
            correlationId=data.get("correlationId", ""),
            occurredAt=occurred_at,
            payload=data.get("payload", {}),
        )


# --- Abstract Ports (messaging.md Abstract Ports section) ---


class PublishFailed(Exception):
    """The event was not published."""


class SubscribeFailed(Exception):
    """The subscriber could not start."""


class HandlerError(Exception):
    """Raised by event handlers to indicate transient or fatal errors."""


class TransientHandlerError(HandlerError):
    """Transient error: message should be retried (not XACK'd)."""


class FatalHandlerError(HandlerError):
    """Fatal error: message should be moved to DLQ."""


class EventPublisher:
    """Abstract port for publishing domain events.

    Domain/application layers depend only on this port.
    """

    def publish(self, envelope: EventEnvelope) -> None:
        """Publish a domain event.

        Raises PublishFailed if the event could not be published.
        """
        raise NotImplementedError


class EventSubscriber:
    """Abstract port for subscribing to event streams.

    Domain/application layers depend only on this port.
    """

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: callable,
    ) -> None:
        """Subscribe to event streams as a consumer group member.

        Args:
            streams: List of stream keys to subscribe to.
            group: Consumer group name.
            consumer_name: Unique consumer instance identifier.
            handler: Callback receiving (EventEnvelope) -> None.
                      May raise TransientHandlerError or FatalHandlerError.

        Raises SubscribeFailed if the subscriber could not start.
        """
        raise NotImplementedError


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
                "itineraryRef": itin.get("itineraryRef"),
                "legs": itin.get("legs", []),
                "priceHint": itin.get("priceHint"),
                "availabilityHint": itin.get("availabilityHint"),
            }
            for itin in itineraries
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
