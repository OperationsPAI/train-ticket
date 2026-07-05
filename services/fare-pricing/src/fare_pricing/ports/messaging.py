from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Protocol

from . import EventEnvelope


class PublishFailed(Exception):
    """Raised when the event bus could not accept a published event."""


class SubscribeFailed(Exception):
    """Raised when the event subscriber could not start or continue."""


class EventPublisher(Protocol):
    """Abstract port for publishing domain events to the event bus.

    Implementation rules (see docs/08-contracts/messaging.md):
    - Target destination is derived from envelope.producer by the adapter.
    - Serialize the entire envelope as a single JSON value in the "envelope" field.
    - Apply retention policy (MAXLEN ~ 100000) on publish.
    - Retry with exponential backoff (3 attempts) on transient failure.
    - Must be thread-safe.
    """

    def publish(self, envelope: EventEnvelope) -> None:
        """Publish an event. Raises PublishFailed on persistent failure."""
        ...


class EventSubscriber(Protocol):
    """Abstract port for consuming event envelopes.

    Producer-only services such as fare-pricing do not start a runtime
    subscriber, but the application seam remains available for tests and future
    consumers. Implementations deduplicate by eventId before invoking handler.
    """

    def subscribe(
        self,
        streams: Sequence[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        """Subscribe and dispatch unique event envelopes to handler."""
        ...
