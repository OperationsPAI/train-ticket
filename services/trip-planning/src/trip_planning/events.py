from __future__ import annotations

from typing import Any, Mapping

from train_ticket_platform.events import EventEnvelope as _PlatformEventEnvelope, canonical_correlation_id, envelope_factory
from train_ticket_platform.ids import new_uuid7
from train_ticket_platform.messaging import FatalHandlerError, HandlerError, PublishFailed, SubscribeFailed, TransientHandlerError


class EventEnvelope(_PlatformEventEnvelope):
    def __init__(
        self,
        eventId: str | None = None,
        eventType: str | None = None,
        producer: str = "trip-planning",
        causationId: str | None = None,
        correlationId: str | None = None,
        occurredAt: Any = None,
        payload: Mapping[str, Any] | None = None,
        schemaVersion: int = 1,
        **kwargs: Any,
    ) -> None:
        super().__init__(
            eventId=eventId,
            eventType=eventType,
            producer=producer,
            causationId=causationId,
            correlationId=correlationId,
            occurredAt=occurredAt,
            payload=payload,
            schemaVersion=schemaVersion,
            **kwargs,
        )

    @classmethod
    def from_json_dict(cls, data: Mapping[str, Any]) -> "EventEnvelope":
        restored = _PlatformEventEnvelope.from_json_dict(data)
        return cls(
            eventId=restored.eventId,
            eventType=restored.eventType,
            producer=restored.producer or "trip-planning",
            causationId=restored.causationId,
            correlationId=restored.correlationId,
            occurredAt=restored.occurredAt,
            payload=restored.payload,
            schemaVersion=restored.schemaVersion,
            traceparent=restored.traceparent or "",
            tracestate=restored.tracestate,
        )


def build_itinerary_proposed_event(
    intent_ref: str,
    itineraries: tuple[dict[str, Any], ...],
    planning_snapshot_refs: tuple[str, ...],
    *,
    correlation_id: str = "",
    causation_id: str = "",
) -> EventEnvelope:
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
    envelope = envelope_factory(
        event_type="ItineraryProposed",
        producer="trip-planning",
        payload=payload,
        correlation_id=canonical_correlation_id(correlation_id),
        causation_id=causation_id or None,
    )
    return EventEnvelope.from_json_dict(envelope.to_json_dict())
