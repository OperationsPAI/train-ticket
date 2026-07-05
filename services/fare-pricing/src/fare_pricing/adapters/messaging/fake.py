from __future__ import annotations

from typing import Any

from ...ports import EventEnvelope
from ...ports.messaging import (
    EventPublisher,
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
