from __future__ import annotations

import threading
from collections.abc import Callable

from trip_planning.application_ports import EventSubscriber
from trip_planning.events import EventEnvelope

from .redis_streams import (
    TRIP_PLANNING_CONSUMER_GROUP,
    TRIP_PLANNING_SUBSCRIPTIONS,
    default_consumer_name,
)


def start_trip_planning_subscription(
    subscriber: EventSubscriber,
    handler: Callable[[EventEnvelope], None],
) -> threading.Thread:
    if hasattr(subscriber, "start_in_background"):
        return subscriber.start_in_background(handler)  # type: ignore[no-any-return, attr-defined]

    thread = threading.Thread(
        target=subscriber.subscribe,
        args=(
            list(TRIP_PLANNING_SUBSCRIPTIONS),
            TRIP_PLANNING_CONSUMER_GROUP,
            default_consumer_name(),
            handler,
        ),
        daemon=True,
        name="trip-planning-subscriber",
    )
    thread.start()
    return thread


__all__ = ["start_trip_planning_subscription"]
