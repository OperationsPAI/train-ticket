from __future__ import annotations

from collections.abc import Callable
import threading
from typing import Any

from train_ticket_platform.messaging import RedisEventPublisher, RedisEventSubscriber, default_consumer_name

TRIP_PLANNING_SUBSCRIPTIONS = (
    "events:place-network",
    "events:service-plan",
    "events:capacity-availability",
)
TRIP_PLANNING_CONSUMER_GROUP = "trip-planning"


def trip_planning_consumer_name() -> str:
    return default_consumer_name(TRIP_PLANNING_CONSUMER_GROUP)


def start_trip_planning_subscription(subscriber: Any, handler: Callable[[Any], None]) -> threading.Thread:
    if hasattr(subscriber, "start_in_background"):
        try:
            return subscriber.start_in_background(
                list(TRIP_PLANNING_SUBSCRIPTIONS),
                TRIP_PLANNING_CONSUMER_GROUP,
                handler,
                consumer_name=trip_planning_consumer_name(),
            )
        except TypeError:
            return subscriber.start_in_background(handler, consumer_name=trip_planning_consumer_name())
    thread = threading.Thread(
        target=subscriber.subscribe,
        args=(list(TRIP_PLANNING_SUBSCRIPTIONS), TRIP_PLANNING_CONSUMER_GROUP, trip_planning_consumer_name(), handler),
        daemon=True,
        name="trip-planning-event-subscriber",
    )
    thread.start()
    return thread
