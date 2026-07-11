from __future__ import annotations

from datetime import date
from typing import Any, Mapping

from fare_pricing.application.service import FarePricingService
from fare_pricing.domain import CapacitySnapshot, PricingError
from fare_pricing.ports import EventEnvelope

CAPACITY_STREAM = "events:capacity-availability"
CAPACITY_EVENT_TYPE = "CapacitySnapshotUpdated"


def handle_capacity_snapshot_updated(service: FarePricingService, envelope: EventEnvelope) -> None:
    """Cache CapacitySnapshotUpdated events for quote-time dynamic pricing."""
    if envelope.event_type != CAPACITY_EVENT_TYPE:
        return
    snapshot = capacity_snapshot_from_payload(envelope.payload)
    service.upsert_capacity_snapshot(snapshot)


def capacity_snapshot_from_payload(payload: Mapping[str, Any]) -> CapacitySnapshot:
    try:
        return CapacitySnapshot(
            segment_ref=str(payload["segmentRef"]),
            departure_date=date.fromisoformat(str(payload["departureDate"])),
            total_capacity=int(payload["totalCapacity"]),
            remaining_capacity=int(payload["remainingCapacity"]),
            snapshot_version=int(payload["snapshotVersion"]),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise PricingError("invalid CapacitySnapshotUpdated payload") from exc
