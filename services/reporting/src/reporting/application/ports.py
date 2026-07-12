from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any, Protocol

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import HandlerResult, HandlerStatus, PublishFailed, SubscribeFailed

EventHandler = Callable[[EventEnvelope], HandlerResult]


class EventPublisher(Protocol):
    def publish(self, envelope: EventEnvelope) -> None:
        """Publish a fully-populated EventEnvelope."""


class EventSubscriber(Protocol):
    def subscribe(
        self,
        streams: Sequence[str],
        group: str,
        consumer_name: str,
        handler: EventHandler,
    ) -> None:
        """Subscribe to event streams as a consumer-group member."""

    def stop(self) -> None:
        """Stop background polling and finish in-flight processing."""

__all__ = [
    "EventEnvelope",
    "EventHandler",
    "EventPublisher",
    "EventSubscriber",
    "HandlerResult",
    "HandlerStatus",
    "PublishFailed",
    "SubscribeFailed",
]
