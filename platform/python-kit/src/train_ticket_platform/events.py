from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from .ids import new_prefixed_uuid7, new_uuid7

ENVELOPE_FIELDS = {"eventId", "eventType", "occurredAt", "correlationId", "causationId", "producer", "schemaVersion", "payload"}
REQUIRED_ENVELOPE_FIELDS = ENVELOPE_FIELDS


def rfc3339_utc(value: datetime) -> str:
    aware = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


class MalformedEnvelopeError(ValueError):
    """Raised when an inbound EventEnvelope violates the wire contract."""


@dataclass(frozen=True, slots=True, init=False)
class EventEnvelope:
    """Contract EventEnvelope with camelCase attribute aliases.

    Wire JSON uses the fields from docs/08-contracts/messaging.md: eight core
    fields plus the optional ``traceparent``/``tracestate`` trace context.
    ``causationId`` is omitted from JSON only when absent because existing
    service contracts allow no causation for externally triggered facts.
    """

    eventId: str
    eventType: str
    occurredAt: datetime | str = field(default_factory=lambda: datetime.now(UTC))
    correlationId: str = field(default_factory=lambda: new_prefixed_uuid7("corr"))
    causationId: str | None = field(default_factory=lambda: new_prefixed_uuid7("cmd"))
    producer: str = ""
    schemaVersion: int = 1
    payload: Mapping[str, Any] = field(default_factory=dict)
    traceparent: str | None = None
    tracestate: str | None = None

    def __init__(
        self,
        eventId: str | None = None,
        eventType: str | None = None,
        occurredAt: datetime | str | None = None,
        correlationId: str | None = None,
        causationId: str | None = None,
        producer: str = "",
        schemaVersion: int = 1,
        payload: Mapping[str, Any] | None = None,
        event_id: str | None = None,
        event_type: str | None = None,
        occurred_at: datetime | str | None = None,
        correlation_id: str | None = None,
        causation_id: str | None = None,
        schema_version: int | None = None,
        traceparent: str | None = None,
        tracestate: str | None = None,
    ) -> None:
        object.__setattr__(self, "eventId", eventId or event_id or new_prefixed_uuid7("evt"))
        object.__setattr__(self, "eventType", eventType or event_type or "")
        object.__setattr__(self, "occurredAt", occurredAt or occurred_at or datetime.now(UTC))
        object.__setattr__(self, "correlationId", correlationId or correlation_id or new_prefixed_uuid7("corr"))
        object.__setattr__(self, "causationId", causationId if causationId is not None else causation_id)
        object.__setattr__(self, "producer", producer)
        object.__setattr__(self, "schemaVersion", schemaVersion if schema_version is None else schema_version)
        object.__setattr__(self, "payload", payload or {})
        context_traceparent = traceparent
        context_tracestate = tracestate
        if context_traceparent is None:
            context_traceparent, context_tracestate = _active_trace_context()
        object.__setattr__(self, "traceparent", context_traceparent)
        object.__setattr__(self, "tracestate", context_tracestate if context_traceparent else None)

    @property
    def event_id(self) -> str:
        return self.eventId

    @property
    def event_type(self) -> str:
        return self.eventType

    @property
    def occurred_at(self) -> datetime | str:
        return self.occurredAt

    @property
    def correlation_id(self) -> str:
        return self.correlationId

    @property
    def causation_id(self) -> str:
        return self.causationId or ""

    @property
    def producer_name(self) -> str:
        return self.producer

    @property
    def schema_version(self) -> int:
        return self.schemaVersion

    def _occurred_at_json(self) -> str:
        if isinstance(self.occurredAt, datetime):
            return rfc3339_utc(self.occurredAt)
        return self.occurredAt

    def to_json_dict(self) -> dict[str, Any]:
        data = {
            "eventId": self.eventId,
            "eventType": self.eventType,
            "occurredAt": self._occurred_at_json(),
            "correlationId": self.correlationId,
            "producer": self.producer,
            "schemaVersion": self.schemaVersion,
            "payload": dict(self.payload),
        }
        if self.causationId:
            data["causationId"] = self.causationId
        if self.traceparent:
            data["traceparent"] = self.traceparent
            if self.tracestate:
                data["tracestate"] = self.tracestate
        return data

    def as_dict(self) -> dict[str, Any]:
        return self.to_json_dict()

    def to_dict(self) -> dict[str, Any]:
        return self.to_json_dict()

    @classmethod
    def from_json_dict(cls, data: Mapping[str, Any]) -> "EventEnvelope":
        if not isinstance(data, Mapping):
            raise MalformedEnvelopeError("event envelope must be a JSON object")
        required_without_cause = REQUIRED_ENVELOPE_FIELDS - {"causationId"}
        missing = [field_name for field_name in required_without_cause if field_name not in data]
        if missing:
            raise MalformedEnvelopeError(f"event envelope missing required fields: {missing}")

        def required_text(field_name: str) -> str:
            value = data[field_name]
            if not isinstance(value, str) or not value.strip():
                raise MalformedEnvelopeError(f"event envelope field {field_name} must be a non-empty string")
            return value

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
        causation_raw = data.get("causationId")
        if causation_raw is not None and not isinstance(causation_raw, str):
            raise MalformedEnvelopeError("event envelope field causationId must be a string when present")
        return cls(
            eventId=required_text("eventId"),
            eventType=required_text("eventType"),
            occurredAt=occurred_at,
            correlationId=required_text("correlationId"),
            causationId=causation_raw,
            producer=required_text("producer"),
            schemaVersion=schema_version,
            payload=payload,
            traceparent=data.get("traceparent") if isinstance(data.get("traceparent"), str) else "",
            tracestate=data.get("tracestate") if isinstance(data.get("tracestate"), str) else None,
        )

    @classmethod
    def from_mapping(cls, data: Mapping[str, Any]) -> "EventEnvelope":
        return cls.from_json_dict(data)


def _active_trace_context() -> tuple[str | None, str | None]:
    try:
        from .observability import otel_tracing_enabled

        if not otel_tracing_enabled():
            return None, None
        from opentelemetry import trace
        from opentelemetry.trace import format_trace_id, format_span_id

        span_context = trace.get_current_span().get_span_context()
    except Exception:
        return None, None
    if not getattr(span_context, "is_valid", False):
        return None, None
    flags = int(getattr(span_context, "trace_flags", 0)) & 0xFF
    traceparent = f"00-{format_trace_id(span_context.trace_id)}-{format_span_id(span_context.span_id)}-{flags:02x}"
    tracestate = str(getattr(span_context, "trace_state", "") or "")
    return traceparent, tracestate or None


def canonical_correlation_id(value: str | None) -> str:
    if not value:
        return new_prefixed_uuid7("corr")
    return value if value.startswith("corr-") else f"corr-{value}"


def canonical_causation_id(value: str | None) -> str:
    if not value:
        return new_prefixed_uuid7("cmd")
    return value


def envelope_factory(
    *,
    event_type: str,
    producer: str,
    payload: Mapping[str, Any] | None = None,
    correlation_id: str | None = None,
    causation_id: str | None = None,
    occurred_at: datetime | None = None,
    schema_version: int = 1,
    event_id: str | None = None,
) -> EventEnvelope:
    return EventEnvelope(
        eventId=event_id or new_prefixed_uuid7("evt"),
        eventType=event_type,
        occurredAt=occurred_at or datetime.now(UTC),
        correlationId=canonical_correlation_id(correlation_id),
        causationId=canonical_causation_id(causation_id),
        producer=producer,
        schemaVersion=schema_version,
        payload=payload or {},
    )


def publish_after_commit(publish: Any, envelope: EventEnvelope) -> None:
    """Publish after state has been committed; publisher errors propagate."""
    publish(envelope)
