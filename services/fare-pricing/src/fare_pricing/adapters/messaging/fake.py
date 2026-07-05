from __future__ import annotations

import json
from collections.abc import Callable
from typing import Any

from ...ports import EventEnvelope
from ...ports.messaging import (
    EventPublisher,
    EventSubscriber,
    PublishFailed,
)


class FakeEventPublisher(EventPublisher):
    """In-memory fake implementation of EventPublisher for tests."""

    def __init__(self) -> None:
        self.events: list[EventEnvelope] = []
        self._fail_on_publish = False

    def publish(self, envelope: EventEnvelope) -> None:
        if self._fail_on_publish:
            raise PublishFailed("Simulated publish failure")
        self.events.append(envelope)

    def fail_next_publish(self) -> None:
        self._fail_on_publish = True

    @property
    def published_events(self) -> list[EventEnvelope]:
        return list(self.events)

    @property
    def published_event_count(self) -> int:
        return len(self.events)

    def last_event(self) -> EventEnvelope | None:
        return self.events[-1] if self.events else None

    def published_envelopes_json(self) -> list[dict[str, Any]]:
        return [e.to_json_dict() for e in self.events]

    def published_envelopes_on_stream(self, stream: str) -> list[EventEnvelope]:
        return [e for e in self.events if f"events:{e.producer}" == stream]

    def clear(self) -> None:
        self.events.clear()
        self._fail_on_publish = False


class FakeEventSubscriber(EventSubscriber):
    """In-memory fake implementation of EventSubscriber for tests."""

    def __init__(self) -> None:
        self.handlers: list[Callable[[EventEnvelope], None]] = []
        self.streams: list[str] = []
        self.group: str = ""
        self.consumer_name: str = ""
        self._started = False
        self._seen_event_ids: set[str] = set()

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        self.streams = list(streams)
        self.group = group
        self.consumer_name = consumer_name
        self.handlers.append(handler)
        self._started = True

    def deliver(self, envelope: EventEnvelope) -> None:
        """Simulate delivering an event with consumer-side eventId deduplication."""
        if envelope.event_id in self._seen_event_ids:
            return
        for handler in self.handlers:
            handler(envelope)
        self._seen_event_ids.add(envelope.event_id)

    def deliver_raw(self, envelope_json: str) -> None:
        """Simulate delivering a raw JSON event to all registered handlers."""
        data = json.loads(envelope_json)
        envelope = EventEnvelope.from_json_dict(data)
        self.deliver(envelope)
