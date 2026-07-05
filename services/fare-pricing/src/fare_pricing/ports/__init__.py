from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Mapping


@dataclass(frozen=True)
class EventEnvelope:
    """Event envelope per messaging.md wire format.

    Field names are Python-idiomatic; JSON serialization uses camelCase.
    JSON wire format:
    {
      "eventId": "evt-<uuid-v7>",
      "eventType": "FareRuleSetPublished",
      "schemaVersion": 1,
      "producer": "fare-pricing",
      "sourceCommandId": "cmd-<uuid-v7>",
      "causationId": "cmd-<uuid-v7>",
      "correlationId": "corr-<uuid-v7>",
      "occurredAt": "2026-07-03T10:30:00.000Z",
      "payload": { ... }
    }
    """

    event_id: str  # "evt-<uuid-v7>"
    event_type: str  # e.g. "FareRuleSetPublished"
    schema_version: int = 1
    producer: str = "fare-pricing"
    source_command_id: str = ""
    causation_id: str = ""
    correlation_id: str = ""
    occurred_at: datetime | None = None  # RFC3339 UTC
    attributes: Mapping[str, str] | None = None
    payload: Mapping[str, Any] | None = None

    def to_json_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {
            "eventId": self.event_id,
            "eventType": self.event_type,
            "schemaVersion": self.schema_version,
            "producer": self.producer,
            "sourceCommandId": self.source_command_id,
            "causationId": self.causation_id,
            "correlationId": self.correlation_id,
        }
        if self.occurred_at is not None:
            d["occurredAt"] = self.occurred_at.strftime("%Y-%m-%dT%H:%M:%S.") + f"{self.occurred_at.microsecond // 1000:03d}Z"
        else:
            d["occurredAt"] = datetime.now().strftime("%Y-%m-%dT%H:%M:%S.") + f"{datetime.now().microsecond // 1000:03d}Z"
        if self.attributes is not None:
            d["attributes"] = dict(self.attributes)
        if self.payload is not None:
            d["payload"] = dict(self.payload)
        return d

    @classmethod
    def from_json_dict(cls, data: dict[str, Any]) -> EventEnvelope:
        occurred_at = None
        if "occurredAt" in data:
            occurred_at = datetime.strptime(data["occurredAt"], "%Y-%m-%dT%H:%M:%S.%fZ")
        return cls(
            event_id=data.get("eventId", ""),
            event_type=data.get("eventType", ""),
            schema_version=data.get("schemaVersion", 1),
            producer=data.get("producer", ""),
            source_command_id=data.get("sourceCommandId", ""),
            causation_id=data.get("causationId", ""),
            correlation_id=data.get("correlationId", ""),
            occurred_at=occurred_at,
            attributes=data.get("attributes"),
            payload=data.get("payload"),
        )
