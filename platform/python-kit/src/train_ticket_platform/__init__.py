"""Shared Python platform kit for Train Ticket services."""

from .events import EventEnvelope, envelope_factory, new_prefixed_uuid7, new_uuid7
from .http import ApiError, canonical_error_body, error_response, register_exception_handlers
from .idempotency import BoundedInMemoryIdempotencyStore, IdempotencyMiddleware, IdempotencyRecord, configure_idempotency_middleware, require_uuid7_idempotency_key
from .messaging import (
    FatalHandlerError,
    HandlerResult,
    HandlerStatus,
    InMemoryEventPublisher,
    InMemoryEventSubscriber,
    PublishFailed,
    RedisEventPublisher,
    RedisEventSubscriber,
    SubscribeFailed,
    TransientHandlerError,
)

__all__ = [
    "ApiError",
    "BoundedInMemoryIdempotencyStore",
    "EventEnvelope",
    "FatalHandlerError",
    "HandlerResult",
    "HandlerStatus",
    "InMemoryEventPublisher",
    "InMemoryEventSubscriber",
    "IdempotencyMiddleware",
    "IdempotencyRecord",
    "PublishFailed",
    "RedisEventPublisher",
    "RedisEventSubscriber",
    "SubscribeFailed",
    "TransientHandlerError",
    "canonical_error_body",
    "configure_idempotency_middleware",
    "envelope_factory",
    "error_response",
    "new_prefixed_uuid7",
    "new_uuid7",
    "register_exception_handlers",
    "require_uuid7_idempotency_key",
]
