from __future__ import annotations

import logging

from train_ticket_platform.events import EventEnvelope
from train_ticket_platform.messaging import RedisEventPublisher, RedisEventSubscriber, default_consumer_name

CORPORATE_TRAVEL_SUBSCRIPTIONS: tuple[str, ...] = ("events:journey-order", "events:payment", "events:post-sales")
CORPORATE_TRAVEL_CONSUMER_GROUP = "corporate-travel"

logger = logging.getLogger("corporate-travel.events")


def corporate_travel_consumer_name() -> str:
    return default_consumer_name(CORPORATE_TRAVEL_CONSUMER_GROUP)


def handle_event(envelope: EventEnvelope) -> None:
    if envelope.eventType in {"JourneyOrderConfirmed", "PaymentCaptured", "PostSalesRefundCompleted", "TripCancelled"}:
        logger.info("corporate travel consumed eventType=%s eventId=%s", envelope.eventType, envelope.eventId)


__all__ = [
    "CORPORATE_TRAVEL_CONSUMER_GROUP",
    "CORPORATE_TRAVEL_SUBSCRIPTIONS",
    "RedisEventPublisher",
    "RedisEventSubscriber",
    "corporate_travel_consumer_name",
    "handle_event",
]
