from __future__ import annotations

import json
import logging
import os
import threading
import time
from collections.abc import Callable
from typing import Any

from trip_planning.events import (
    EventEnvelope,
    FatalHandlerError,
    PublishFailed,
    SubscribeFailed,
    TransientHandlerError,
)

try:  # Redis imports are intentionally confined to this adapter package.
    import redis
except ImportError:  # pragma: no cover - dependency is installed in normal runtime
    redis = None  # type: ignore[assignment]

logger = logging.getLogger(__name__)

MAXLEN = 100000
MAX_RETRIES = 3
RETRY_BACKOFF_SECONDS = (0.1, 0.3, 0.9)
MAX_DELIVERY_ATTEMPTS = 5
CLAIM_MIN_IDLE_MS = 60000
CLAIM_COUNT = 100
POLL_BLOCK_MS = 2000
POLL_COUNT = 10

TRIP_PLANNING_SUBSCRIPTIONS = (
    "events:place-network",
    "events:service-plan",
    "events:capacity-availability",
)
TRIP_PLANNING_CONSUMER_GROUP = "trip-planning"


class RedisEventPublisher:
    """Redis Streams EventPublisher implementation."""

    def __init__(self, redis_client: Any | None = None, redis_url: str | None = None) -> None:
        if redis_client is not None:
            self._redis = redis_client
        elif redis is not None:
            self._redis = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"))
        else:
            raise PublishFailed("redis-py is not installed")

    def publish(self, envelope: EventEnvelope) -> None:
        stream_key = f"events:{envelope.producer}"
        payload = json.dumps(envelope.to_json_dict(), ensure_ascii=False, separators=(",", ":"))
        last_error: Exception | None = None
        for attempt in range(MAX_RETRIES):
            try:
                self._redis.xadd(stream_key, {"envelope": payload}, maxlen=MAXLEN, approximate=True)
                return
            except Exception as exc:  # pragma: no cover - exercised with fake clients in tests
                last_error = exc
                if attempt < MAX_RETRIES - 1:
                    time.sleep(RETRY_BACKOFF_SECONDS[attempt])
        raise PublishFailed(f"Failed to publish event {envelope.eventId} after {MAX_RETRIES} attempts") from last_error


class RedisEventSubscriber:
    """Redis Streams EventSubscriber implementation with a stoppable polling lifecycle."""

    def __init__(self, redis_client: Any | None = None, redis_url: str | None = None) -> None:
        if redis_client is not None:
            self._redis = redis_client
        elif redis is not None:
            self._redis = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"))
        else:
            raise SubscribeFailed("redis-py is not installed")
        self._dedup: set[str] = set()
        self._stop_requested = threading.Event()

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        try:
            for stream in streams:
                self._ensure_group(stream, group)
        except Exception as exc:
            raise SubscribeFailed("subscriber could not start Redis Streams consumer group") from exc

        next_recovery_at = 0.0
        self._stop_requested.clear()
        while not self._stop_requested.is_set():
            try:
                now = time.monotonic()
                if now >= next_recovery_at:
                    self._recover_pending(streams, group, consumer_name, handler)
                    next_recovery_at = now + (CLAIM_MIN_IDLE_MS / 1000)

                results = self._redis.xreadgroup(
                    group,
                    consumer_name,
                    {stream: ">" for stream in streams},
                    count=POLL_COUNT,
                    block=POLL_BLOCK_MS,
                )
                self._process_results(results, group, handler)
            except Exception as exc:
                if self._stop_requested.is_set():
                    break
                if isinstance(exc, (TransientHandlerError, FatalHandlerError)):
                    raise
                raise SubscribeFailed("subscriber could not poll Redis Streams") from exc

    def stop(self) -> None:
        self._stop_requested.set()

    def shutdown(self) -> None:
        self.stop()

    def _ensure_group(self, stream: str, group: str) -> None:
        try:
            self._redis.xgroup_create(stream, group, id="$", mkstream=True)
        except Exception as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def _recover_pending(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        for stream in streams:
            claimed = self._redis.xautoclaim(stream, group, consumer_name, CLAIM_MIN_IDLE_MS, "0", count=CLAIM_COUNT)
            messages = claimed[1] if claimed and len(claimed) > 1 else []
            for msg_id, msg_data in messages:
                self._process_message(stream, group, msg_id, msg_data, handler)

    def _process_results(self, results: Any, group: str, handler: Callable[[EventEnvelope], None]) -> None:
        for stream_name, messages in results or []:
            stream = stream_name.decode("utf-8") if isinstance(stream_name, bytes) else str(stream_name)
            for msg_id, msg_data in messages:
                self._process_message(stream, group, msg_id, msg_data, handler)

    def _process_message(
        self,
        stream: str,
        group: str,
        msg_id: bytes | str,
        msg_data: MappingLike,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
        envelope_json = self._extract_envelope_json(msg_data)
        try:
            envelope = EventEnvelope.from_json_dict(json.loads(envelope_json))
        except Exception:
            self._move_to_dlq(stream, envelope_json or "{}")
            self._xack(stream, group, msg_id_str)
            return

        if envelope.eventId in self._dedup:
            self._xack(stream, group, msg_id_str)
            return
        if self._delivery_count(stream, group, msg_id_str) >= MAX_DELIVERY_ATTEMPTS:
            self._move_to_dlq(stream, envelope_json)
            self._xack(stream, group, msg_id_str)
            return
        try:
            handler(envelope)
        except TransientHandlerError:
            return
        except FatalHandlerError:
            self._move_to_dlq(stream, envelope_json)
            self._xack(stream, group, msg_id_str)
            return
        self._dedup.add(envelope.eventId)
        self._xack(stream, group, msg_id_str)

    def _extract_envelope_json(self, msg_data: MappingLike) -> str:
        envelope = msg_data.get(b"envelope") if hasattr(msg_data, "get") else None
        if envelope is None and hasattr(msg_data, "get"):
            envelope = msg_data.get("envelope")
        if isinstance(envelope, bytes):
            return envelope.decode("utf-8")
        if isinstance(envelope, str):
            return envelope
        return ""

    def _delivery_count(self, stream: str, group: str, msg_id: str) -> int:
        try:
            pending = self._redis.xpending_range(stream, group, min=msg_id, max=msg_id, count=1)
        except Exception:
            return 0
        if not pending:
            return 0
        first = pending[0]
        if hasattr(first, "get"):
            return int(first.get("times_delivered", first.get(b"times_delivered", 0)))
        return 0

    def _move_to_dlq(self, stream: str, envelope_json: str) -> None:
        self._redis.xadd(f"{stream}:dlq", {"envelope": envelope_json}, maxlen=MAXLEN, approximate=True)

    def _xack(self, stream: str, group: str, msg_id: str) -> None:
        self._redis.xack(stream, group, msg_id)


MappingLike = Any
