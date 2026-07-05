from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol


@dataclass(frozen=True, slots=True)
class EventEnvelope:
    eventId: str
    eventType: str
    occurredAt: str
    correlationId: str
    causationId: str
    producer: str
    schemaVersion: int
    payload: Mapping[str, Any]

    def as_dict(self) -> dict[str, Any]:
        return {
            "eventId": self.eventId,
            "eventType": self.eventType,
            "occurredAt": self.occurredAt,
            "correlationId": self.correlationId,
            "causationId": self.causationId,
            "producer": self.producer,
            "schemaVersion": self.schemaVersion,
            "payload": dict(self.payload),
        }


class PublishFailed(RuntimeError):
    pass


class SubscribeFailed(RuntimeError):
    pass


class HandlerStatus(str, Enum):
    SUCCESS = "SUCCESS"
    TRANSIENT_ERROR = "TRANSIENT_ERROR"
    FATAL_ERROR = "FATAL_ERROR"


@dataclass(frozen=True, slots=True)
class HandlerResult:
    status: HandlerStatus
    message: str = ""

    @classmethod
    def success(cls) -> "HandlerResult":
        return cls(HandlerStatus.SUCCESS)

    @classmethod
    def transient_error(cls, message: str) -> "HandlerResult":
        return cls(HandlerStatus.TRANSIENT_ERROR, message)

    @classmethod
    def fatal_error(cls, message: str) -> "HandlerResult":
        return cls(HandlerStatus.FATAL_ERROR, message)


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
