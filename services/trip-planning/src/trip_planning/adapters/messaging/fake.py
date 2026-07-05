from __future__ import annotations

from typing import Any, Callable

from trip_planning.application_ports import EventPublisher, EventSubscriber
from trip_planning.events import EventEnvelope


class FakeEventPublisher(EventPublisher):
    """In-memory fake implementation of EventPublisher for testing."""

    def __init__(self) -> None:
        self.published: list[EventEnvelope] = []
        self.fail_on_publish: bool = False

    def publish(self, envelope: EventEnvelope) -> None:
        if self.fail_on_publish:
            from trip_planning.events import PublishFailed
            raise PublishFailed("simulated publish failure")
        self.published.append(envelope)


class FakeEventSubscriber(EventSubscriber):
    """In-memory fake implementation of EventSubscriber for testing."""

    def __init__(self) -> None:
        self.handlers: list[tuple[list[str], str, str, Callable]] = []
        self.received: list[EventEnvelope] = []
        self.dedup_seen: set[str] = set()

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        self.handlers.append((streams, group, consumer_name, handler))

    def simulate_message(self, envelope: EventEnvelope) -> None:
        """Simulate receiving a message from the stream."""
        if envelope.eventId in self.dedup_seen:
            return  # dedup
        self.dedup_seen.add(envelope.eventId)
        self.received.append(envelope)
        # Find the handler and call it
        for _streams, _group, _consumer_name, handler in self.handlers:
            handler(envelope)
