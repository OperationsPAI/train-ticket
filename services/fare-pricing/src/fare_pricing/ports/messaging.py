from __future__ import annotations

from typing import Protocol

from . import EventEnvelope


class PublishFailed(Exception):
    """Raised when the event bus could not accept a published event."""


class EventPublisher(Protocol):
    """Abstract port for publishing domain events to the event bus.

    Implementation rules (see docs/08-contracts/messaging.md):
    - Target stream is derived from envelope.producer -> "events:<producer>".
    - Serialize the entire envelope as a single JSON value in the "envelope" field.
    - Apply retention policy (MAXLEN ~ 100000) on publish.
    - Retry with exponential backoff (3 attempts) on transient failure.
    - Must be thread-safe.
    """

    def publish(self, envelope: EventEnvelope) -> None:
        """Publish an event. Raises PublishFailed on persistent failure."""
        ...


class SubscribeFailed(Exception):
    """Raised when the subscriber could not start."""


class HandlerError(Exception):
    """Base for errors returned by the event handler."""


class TransientHandlerError(HandlerError):
    """Transient error; the message should be retried."""


class FatalHandlerError(HandlerError):
    """Fatal error; the message should be moved to DLQ."""


class EventSubscriber(Protocol):
    """Abstract port for subscribing to event streams as a consumer group member.

    Implementation rules (see docs/08-contracts/messaging.md):
    - Create consumer group on startup (XGROUP CREATE ... MKSTREAM).
    - Poll with XREADGROUP in a loop.
    - Call handler with deserialized EventEnvelope.
    - XACK on success, do NOT XACK on transient error, move to DLQ on fatal.
    - Track delivery attempts; move to DLQ after 5 attempts.
    - Run XAUTOCLAIM every 60s for recovery.
    - Graceful shutdown support.
    """

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: EventHandler,
    ) -> None:
        """Start subscribing. Raises SubscribeFailed on startup failure."""
        ...


class EventHandler(Protocol):
    """Callback invoked by EventSubscriber for each received event."""

    def __call__(self, envelope: EventEnvelope) -> None:
        """Process an event. Raises TransientHandlerError or FatalHandlerError."""
        ...
