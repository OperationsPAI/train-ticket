from __future__ import annotations

from typing import Protocol

from . import EventEnvelope


class PublishFailed(Exception):
    """Raised when the event bus could not accept a published event."""


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
