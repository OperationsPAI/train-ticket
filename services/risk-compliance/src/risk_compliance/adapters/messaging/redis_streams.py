from __future__ import annotations

import json
import os
from typing import Any

from train_ticket_platform.messaging import RedisEventPublisher, RedisEventSubscriber as PlatformRedisEventSubscriber, default_consumer_name
from risk_compliance.application import IDENTITY_PURCHASE_LIMIT_EVENT_TYPES

RISK_COMPLIANCE_PRODUCER = "risk-compliance"
JOURNEY_ORDER_STREAM = "events:journey-order"
BOOKING_ORCHESTRATION_STREAM = "events:booking-orchestration"
IDENTITY_VERIFICATION_STREAM = "events:identity-verification"
RISK_COMPLIANCE_SUBSCRIPTIONS: tuple[str, ...] = (
    JOURNEY_ORDER_STREAM,
    BOOKING_ORCHESTRATION_STREAM,
    IDENTITY_VERIFICATION_STREAM,
)
RISK_COMPLIANCE_CONSUMER_GROUP = "risk-compliance"


def source_stream_for(envelope: Any) -> str:
    streams = {
        "journey-order": JOURNEY_ORDER_STREAM,
        "booking-orchestration": BOOKING_ORCHESTRATION_STREAM,
        "identity-verification": IDENTITY_VERIFICATION_STREAM,
    }
    return streams.get(envelope.producer, envelope.producer)


class RedisEventSubscriber(PlatformRedisEventSubscriber):
    def start_independent_streams(self, streams: tuple[str, ...], group: str, handler: Any, *, consumer_name: str) -> None:
        workers = int(os.environ.get("RISK_STREAM_WORKERS", "4"))
        if workers < 1:
            raise ValueError("RISK_STREAM_WORKERS must be positive")
        for index, stream in enumerate(streams):
            for worker in range(workers):
                super().start_in_background((stream,), group, handler, consumer_name=f"{consumer_name}-{index}-{worker}")

    def _ignored_entry(self, fields: Any) -> bool:
        try:
            raw = json.loads(self._extract_envelope_json(fields))
            if not isinstance(raw, dict) or not all(raw.get(field) for field in ("eventId", "eventType", "producer", "occurredAt", "correlationId")):
                return False
            if not isinstance(raw.get("payload"), dict) or raw.get("schemaVersion") != 1:
                return False
            envelope = self._deserialize_envelope(raw)
        except (ValueError, TypeError, KeyError):
            return False
        return envelope.eventType not in IDENTITY_PURCHASE_LIMIT_EVENT_TYPES | {
            "JourneyOrderCreated", "RiskAssessmentRequested", "AccountRegistered", "PaymentCaptured", "PaymentFailed", "PostSalesApplied"
        }

    def _process_results(self, results: Any, group: str, consumer_name: str, handler: Any) -> None:
        for stream_name, messages in results or []:
            stream = stream_name.decode("utf-8") if isinstance(stream_name, bytes) else str(stream_name)
            ignored = [entry_id for entry_id, fields in messages if self._ignored_entry(fields)]
            if ignored:
                self._client.xack(stream, group, *ignored)
            ignored_ids = set(ignored)
            selected = [(entry_id, fields) for entry_id, fields in messages if entry_id not in ignored_ids]
            if selected:
                super()._process_results([(stream, selected)], group, consumer_name, handler)

    def _process_entry(self, stream: str, group: str, entry_id: str, fields: Any, handler: Any,
                       delivery_count: int, consumer_name: str | None = None) -> None:
        if self._ignored_entry(fields):
            self._client.xack(stream, group, entry_id)
            return
        super()._process_entry(stream, group, entry_id, fields, handler, delivery_count, consumer_name)


def risk_compliance_consumer_name() -> str:
    return default_consumer_name(RISK_COMPLIANCE_CONSUMER_GROUP)

__all__ = [
    "RISK_COMPLIANCE_CONSUMER_GROUP",
    "RISK_COMPLIANCE_PRODUCER",
    "RISK_COMPLIANCE_SUBSCRIPTIONS",
    "BOOKING_ORCHESTRATION_STREAM",
    "IDENTITY_VERIFICATION_STREAM",
    "JOURNEY_ORDER_STREAM",
    "RedisEventPublisher",
    "RedisEventSubscriber",
    "risk_compliance_consumer_name",
    "source_stream_for",
]
