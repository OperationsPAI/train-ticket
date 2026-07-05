from __future__ import annotations

from typing import Any

from fare_pricing.ports import EventEnvelope
from fare_pricing.ports.messaging import PublishFailed
from train_ticket_platform.messaging import InMemoryEventSubscriber as FakeEventSubscriber


class FakeEventPublisher:
    def __init__(self) -> None:
        self.published_events: list[EventEnvelope] = []
        self._fail_next = False

    @property
    def published_event_count(self) -> int:
        return len(self.published_events)

    def publish(self, envelope: EventEnvelope) -> None:
        if self._fail_next:
            self._fail_next = False
            raise PublishFailed("configured publish failure")
        self.published_events.append(envelope)

    def fail_next_publish(self) -> None:
        self._fail_next = True

    def last_event(self) -> EventEnvelope | None:
        return self.published_events[-1] if self.published_events else None

    def published_envelopes_on_stream(self, stream: str) -> list[EventEnvelope]:
        expected = stream.removeprefix("events:")
        return [event for event in self.published_events if event.producer == expected]


__all__ = ["FakeEventPublisher", "FakeEventSubscriber"]
