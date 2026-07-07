from __future__ import annotations

from collections.abc import Callable
import json
import logging
import threading
import time
from typing import Any, Mapping

from train_ticket_platform.messaging import RedisEventPublisher, RedisEventSubscriber as PlatformRedisEventSubscriber, default_consumer_name

from trip_planning.events import EventEnvelope

TRIP_PLANNING_SUBSCRIPTIONS = (
    "events:place-network",
    "events:service-plan",
    "events:capacity-availability",
)
TRIP_PLANNING_CONSUMER_GROUP = "trip-planning"
SUBSCRIBER_RESTART_DELAY_SECONDS = 1.0

logger = logging.getLogger(__name__)


def trip_planning_consumer_name() -> str:
    return default_consumer_name(TRIP_PLANNING_CONSUMER_GROUP)


def start_trip_planning_subscription(subscriber: Any, handler: Callable[[Any], None]) -> threading.Thread:
    thread = threading.Thread(
        target=_run_with_restart,
        args=(subscriber, handler),
        daemon=True,
        name="trip-planning-event-subscriber-supervisor",
    )
    thread.start()
    return thread


def _run_with_restart(subscriber: Any, handler: Callable[[Any], None]) -> None:
    while not _stop_requested(subscriber):
        try:
            _subscribe_once(subscriber, handler)
            return
        except Exception:
            if _stop_requested(subscriber):
                return
            logger.error("trip-planning event subscriber stopped unexpectedly; restarting", exc_info=True)
            time.sleep(SUBSCRIBER_RESTART_DELAY_SECONDS)


def _subscribe_once(subscriber: Any, handler: Callable[[Any], None]) -> None:
    subscriber.subscribe(
        list(TRIP_PLANNING_SUBSCRIPTIONS),
        TRIP_PLANNING_CONSUMER_GROUP,
        trip_planning_consumer_name(),
        handler,
    )


def _stop_requested(subscriber: Any) -> bool:
    stop_requested = getattr(subscriber, "_stop_requested", None)
    if hasattr(stop_requested, "is_set"):
        return bool(stop_requested.is_set())
    stopped = getattr(subscriber, "stopped", None)
    if isinstance(stopped, bool):
        return stopped
    return False


def _extract_envelope_json(fields: Mapping[Any, Any]) -> str:
    for key in (b"envelope", "envelope", b"d", "d"):
        envelope = fields.get(key)
        if isinstance(envelope, bytes):
            return envelope.decode("utf-8")
        if isinstance(envelope, str):
            return envelope
    return ""


def replay_trip_planning_streams(redis_client: Any, handler: Callable[[Any], None]) -> None:
    for stream in TRIP_PLANNING_SUBSCRIPTIONS:
        _ensure_group_before_replay(redis_client, stream)
        for _entry_id, fields in redis_client.xrange(stream, min="-", max="+") or []:
            envelope_json = _extract_envelope_json(fields)
            if not envelope_json:
                continue
            handler(EventEnvelope.from_json_dict(json.loads(envelope_json)))


def _ensure_group_before_replay(redis_client: Any, stream: str) -> None:
    create_group = getattr(redis_client, "xgroup_create", None)
    if not callable(create_group):
        return
    try:
        create_group(stream, TRIP_PLANNING_CONSUMER_GROUP, id="$", mkstream=True)
    except Exception as exc:
        if "BUSYGROUP" not in str(exc):
            raise


class RedisEventSubscriber(PlatformRedisEventSubscriber):
    def replay(self, handler: Callable[[Any], None]) -> None:
        replay_trip_planning_streams(self._client, handler)


def create_redis_event_subscriber() -> RedisEventSubscriber:
    return RedisEventSubscriber()
