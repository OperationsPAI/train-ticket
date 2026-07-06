from __future__ import annotations

from fare_pricing.ports import EventEnvelope
from fare_pricing.ports.messaging import PublishFailed
from train_ticket_platform.messaging import InMemoryEventSubscriber as FakeEventSubscriber


class FakeEventPublisher:
    def __init__(self) -> None:
        self.published_events: list[EventEnvelope] = []
        self._fail_next = False
        self._publish_attempts = 0
        self._fail_on_publish_numbers: set[int] = set()

    @property
    def published_event_count(self) -> int:
        return len(self.published_events)

    def publish(self, envelope: EventEnvelope) -> None:
        self._publish_attempts += 1
        if self._fail_next:
            self._fail_next = False
            raise PublishFailed("configured publish failure")
        if self._publish_attempts in self._fail_on_publish_numbers:
            self._fail_on_publish_numbers.remove(self._publish_attempts)
            raise PublishFailed("configured publish failure")
        self.published_events.append(envelope)

    def fail_next_publish(self) -> None:
        self._fail_next = True

    def fail_on_publish_number(self, publish_number: int) -> None:
        self._fail_on_publish_numbers.add(publish_number)

    def last_event(self) -> EventEnvelope | None:
        return self.published_events[-1] if self.published_events else None

    def published_envelopes_on_stream(self, stream: str) -> list[EventEnvelope]:
        expected = stream.removeprefix("events:")
        return [event for event in self.published_events if event.producer == expected]


__all__ = ["FakeEventPublisher", "FakeEventSubscriber"]
