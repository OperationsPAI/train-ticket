from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any


def _format_rfc3339_utc(dt: datetime) -> str:
    utc_dt = dt.astimezone(UTC) if dt.tzinfo is not None else dt.replace(tzinfo=UTC)
    return utc_dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc_dt.microsecond // 1000:03d}Z"


class MalformedEnvelopeError(ValueError):
    """Raised when an inbound event envelope violates the wire contract."""


@dataclass(frozen=True)
class EventEnvelope:
    """Contract EventEnvelope wire shape.

    JSON fields follow docs/08-contracts/messaging.md: eventId, eventType,
    occurredAt, correlationId, causationId, producer, schemaVersion, payload.
    Unknown fields are rejected on ingress.
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
        data = {
            "eventId": self.event_id,
            "eventType": self.event_type,
            "occurredAt": _format_rfc3339_utc(self.occurred_at),
            "correlationId": self.correlation_id,
            "producer": self.producer,
            "schemaVersion": self.schema_version,
            "payload": dict(self.payload),
        }
        if self.causation_id:
            data["causationId"] = self.causation_id
        return data

    @classmethod
    def from_json_dict(cls, data: dict[str, Any]) -> EventEnvelope:
        if not isinstance(data, dict):
            raise MalformedEnvelopeError("event envelope must be a JSON object")

        required_fields = {"eventId", "eventType", "occurredAt", "correlationId", "producer", "schemaVersion", "payload"}
        allowed_fields = required_fields | {"causationId"}
        unknown_fields = set(data) - allowed_fields
        if unknown_fields:
            raise MalformedEnvelopeError(f"event envelope contains unknown fields: {sorted(unknown_fields)}")

        missing_fields = [field_name for field_name in required_fields if field_name not in data]
        if missing_fields:
            raise MalformedEnvelopeError(f"event envelope missing required fields: {missing_fields}")

        def required_text(field_name: str) -> str:
            value = data[field_name]
            if not isinstance(value, str) or not value.strip():
                raise MalformedEnvelopeError(f"event envelope field {field_name} must be a non-empty string")
            return value

        event_id = required_text("eventId")
        event_type = required_text("eventType")
        correlation_id = required_text("correlationId")
        producer = required_text("producer")
        causation_id_raw = data.get("causationId", "")
        if causation_id_raw is None:
            causation_id = ""
        elif isinstance(causation_id_raw, str):
            causation_id = causation_id_raw
        else:
            raise MalformedEnvelopeError("event envelope field causationId must be a string when present")

        occurred_at_raw = required_text("occurredAt")
        try:
            occurred_at = datetime.fromisoformat(occurred_at_raw.replace("Z", "+00:00")).astimezone(UTC)
        except ValueError as exc:
            raise MalformedEnvelopeError("event envelope field occurredAt must be an RFC3339 timestamp") from exc

        schema_version = data["schemaVersion"]
        if not isinstance(schema_version, int) or isinstance(schema_version, bool) or schema_version < 1:
            raise MalformedEnvelopeError("event envelope field schemaVersion must be a positive integer")

        payload = data["payload"]
        if not isinstance(payload, Mapping):
            raise MalformedEnvelopeError("event envelope field payload must be an object")

        return cls(
            event_id=event_id,
            event_type=event_type,
            occurred_at=occurred_at,
            correlation_id=correlation_id,
            causation_id=causation_id,
            producer=producer,
            schema_version=schema_version,
            payload=payload,
        )
