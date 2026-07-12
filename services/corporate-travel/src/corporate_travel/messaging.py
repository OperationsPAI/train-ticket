from __future__ import annotations

import logging
from typing import Callable

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import RedisEventPublisher, RedisEventSubscriber, default_consumer_name

from .application import CorporateTravelService

CORPORATE_TRAVEL_SUBSCRIPTIONS: tuple[str, ...] = ("events:journey-order", "events:payment", "events:post-sales")
CORPORATE_TRAVEL_CONSUMER_GROUP = "corporate-travel"
CONSUMED_EVENT_TYPES = {"JourneyOrderConfirmed", "PaymentCaptured", "PostSalesRefundCompleted", "TripCancelled"}

logger = logging.getLogger("corporate-travel.events")


def corporate_travel_consumer_name() -> str:
    return default_consumer_name(CORPORATE_TRAVEL_CONSUMER_GROUP)


def build_event_handler(service: CorporateTravelService) -> Callable[[EventEnvelope], None]:
    def handle(envelope: EventEnvelope) -> None:
        if envelope.eventType not in CONSUMED_EVENT_TYPES:
            return
        service.handle_event(envelope)
        logger.info("corporate travel consumed eventType=%s eventId=%s", envelope.eventType, envelope.eventId)

    return handle


def handle_event(envelope: EventEnvelope) -> None:
    """Compatibility hook for tests; create_app wires a service-bound handler."""
    from .api import get_default_service

    build_event_handler(get_default_service())(envelope)


__all__ = [
    "CORPORATE_TRAVEL_CONSUMER_GROUP",
    "CORPORATE_TRAVEL_SUBSCRIPTIONS",
    "RedisEventPublisher",
    "RedisEventSubscriber",
    "build_event_handler",
    "corporate_travel_consumer_name",
    "handle_event",
]
