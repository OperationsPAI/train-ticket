from __future__ import annotations

import os

from fastapi import FastAPI

from .adapters.messaging.config import FARE_PRICING_CONSUMER_GROUP, FARE_PRICING_SUBSCRIPTION_STREAMS
from .adapters.messaging.subscriber import RedisEventSubscriber
from .ids import uuid7
from .ports import EventEnvelope
from .ports.messaging import EventSubscriber


def handle_inbound_event(envelope: EventEnvelope) -> None:
    """Handle inbound fare-pricing stream events.

    Phase-1 fare-pricing events are rule-set publication facts. The Redis adapter
    performs envelope validation, broker retry/DLQ behavior, and eventId dedup;
    no additional projection is required by this service yet.
    """
    return None


def configure_event_subscriber(app: FastAPI, subscriber: EventSubscriber | None = None) -> EventSubscriber:
    """Start the production subscriber lifecycle for this service."""
    event_subscriber = subscriber or RedisEventSubscriber(os.environ.get("REDIS_URL"))
    event_subscriber.subscribe(
        streams=FARE_PRICING_SUBSCRIPTION_STREAMS,
        group=FARE_PRICING_CONSUMER_GROUP,
        consumer_name=f"fare-pricing-{uuid7()}",
        handler=handle_inbound_event,
    )
    app.state.event_subscriber = event_subscriber
    if hasattr(app, "add_event_handler"):
        app.add_event_handler("shutdown", event_subscriber.stop)
    elif hasattr(app, "router") and hasattr(app.router, "add_event_handler"):
        app.router.add_event_handler("shutdown", event_subscriber.stop)
    return event_subscriber
