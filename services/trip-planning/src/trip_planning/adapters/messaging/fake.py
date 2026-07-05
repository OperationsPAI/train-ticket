from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Any

from trip_planning.events import EventEnvelope, PublishFailed


class FakeEventPublisher:
    def __init__(self) -> None:
        self.published: list[EventEnvelope] = []
        self.fail_on_publish = False

    def publish(self, envelope: EventEnvelope) -> None:
        if self.fail_on_publish:
            raise PublishFailed("configured publish failure")
        self.published.append(envelope)


class FakeEventSubscriber:
    def __init__(self, envelopes: Sequence[EventEnvelope] | None = None) -> None:
        self.handlers: list[tuple[Sequence[str], str, str, Callable[[EventEnvelope], Any]]] = []
        self.seen: set[str] = set()
        self.stopped = False
        for envelope in envelopes or ():
            self.simulate_message(envelope)

    def subscribe(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        self.handlers.append((list(streams), group, consumer_name, handler))

    def simulate_message(self, envelope: EventEnvelope) -> None:
        if envelope.eventId in self.seen:
            return
        self.seen.add(envelope.eventId)
        for _streams, _group, _consumer_name, handler in self.handlers:
            handler(envelope)

    def stop(self) -> None:
        self.stopped = True

    def shutdown(self) -> None:
        self.stop()
