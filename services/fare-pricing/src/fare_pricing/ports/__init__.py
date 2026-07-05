from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Mapping


def _format_rfc3339_utc(dt: datetime) -> str:
    utc_dt = dt.astimezone(UTC) if dt.tzinfo is not None else dt.replace(tzinfo=UTC)
    return utc_dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc_dt.microsecond // 1000:03d}Z"


@dataclass(frozen=True)
class EventEnvelope:
    """Contract EventEnvelope with the exact eight wire fields.

    JSON field names follow docs/08-contracts/shared-primitives.md §1:
    eventId, eventType, occurredAt, correlationId, causationId, producer,
    schemaVersion, payload.
    """

    event_id: str
    event_type: str
    occurred_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    correlation_id: str = ""
    causation_id: str = ""
    producer: str = "fare-pricing"
    schema_version: int = 1
    payload: Mapping[str, Any] = field(default_factory=dict)

    def to_json_dict(self) -> dict[str, Any]:
        return {
            "eventId": self.event_id,
            "eventType": self.event_type,
            "occurredAt": _format_rfc3339_utc(self.occurred_at),
            "correlationId": self.correlation_id,
            "causationId": self.causation_id,
            "producer": self.producer,
            "schemaVersion": self.schema_version,
            "payload": dict(self.payload),
        }

    @classmethod
    def from_json_dict(cls, data: dict[str, Any]) -> EventEnvelope:
        occurred_at_raw = data.get("occurredAt")
        if isinstance(occurred_at_raw, str):
            occurred_at = datetime.fromisoformat(occurred_at_raw.replace("Z", "+00:00")).astimezone(UTC)
        else:
            occurred_at = datetime.now(UTC)
        return cls(
            event_id=data.get("eventId", ""),
            event_type=data.get("eventType", ""),
            occurred_at=occurred_at,
            correlation_id=data.get("correlationId", ""),
            causation_id=data.get("causationId", ""),
            producer=data.get("producer", ""),
            schema_version=data.get("schemaVersion", 1),
            payload=data.get("payload") or {},
        )
