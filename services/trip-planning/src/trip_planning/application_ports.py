from __future__ import annotations

from collections.abc import Callable
from typing import Protocol

from .events import EventEnvelope


class EventPublisher(Protocol):
    """Abstract port for publishing domain events."""

    def publish(self, envelope: EventEnvelope) -> None:
        """Publish a fully populated EventEnvelope."""
        ...


class EventSubscriber(Protocol):
    """Abstract port for subscribing to event streams as a consumer group member."""

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        """Start consuming envelopes from the configured streams."""
        ...
