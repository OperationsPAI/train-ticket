from __future__ import annotations

import json
import logging
import os
import time
from collections import OrderedDict
from collections.abc import Callable
from threading import Event, Thread
from typing import Any

from ...ports import EventEnvelope
from ...ports.messaging import (
    EventSubscriber,
    FatalHandlerError,
    SubscribeFailed,
    TransientHandlerError,
)

logger = logging.getLogger(__name__)


class RedisEventSubscriber(EventSubscriber):
    """Redis Streams implementation of EventSubscriber.

    Uses XREADGROUP consumer groups, XAUTOCLAIM recovery, and DLQ.
    Only Redis types/imports live in this module (contract rule).
    """

    def __init__(self, redis_url: str | None = None, dedup_max_entries: int = 10000) -> None:
        import redis as _redis  # type: ignore[import-untyped]

        self._redis_url = redis_url or os.environ.get("REDIS_URL", "redis://localhost:6379")
        self._client: _redis.Redis | None = None  # type: ignore[name-defined]
        self._stop_event = Event()
        self._threads: list[Thread] = []
        self._dedup_max_entries = dedup_max_entries
        self._seen_event_ids: OrderedDict[str, None] = OrderedDict()

    def _get_client(self) -> Any:  # type: ignore[type-arg]
        import redis as _redis  # type: ignore[import-untyped]

        if self._client is None:
            self._client = _redis.from_url(self._redis_url, decode_responses=True)  # type: ignore[attr-defined]
        return self._client

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        client = self._get_client()
        import redis as _redis  # type: ignore[import-untyped]

        # Create consumer groups for each stream
        for stream_key in streams:
            try:
                client.xgroup_create(stream_key, group, id="$", mkstream=True)
            except _redis.ResponseError as exc:
                if "BUSYGROUP" not in str(exc):
                    raise SubscribeFailed(f"Failed to create group for {stream_key}: {exc}") from exc
                # BUSYGROUP means the group already exists — safe to ignore

        # Start a polling thread per stream
        for stream_key in streams:
            thread = Thread(
                target=self._poll_stream,
                args=(stream_key, group, consumer_name, handler),
                daemon=True,
            )
            thread.start()
            self._threads.append(thread)

        # Start a recovery thread
        recovery_thread = Thread(
            target=self._recovery_loop,
            args=(streams, group, consumer_name, handler),
            daemon=True,
        )
        recovery_thread.start()
        self._threads.append(recovery_thread)

    def _poll_stream(
        self,
        stream_key: str,
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        client = self._get_client()
        import redis as _redis  # type: ignore[import-untyped]

        while not self._stop_event.is_set():
            try:
                result = client.xreadgroup(
                    group,
                    consumer_name,
                    {stream_key: ">"},
                    count=10,
                    block=2000,
                )
                if not result:
                    continue

                for stream_name, messages in result:
                    for msg_id, msg_data in messages:
                        if self._stop_event.is_set():
                            return

                        envelope_json = msg_data.get("envelope", "{}")
                        try:
                            envelope_data = json.loads(envelope_json)
                            envelope = EventEnvelope.from_json_dict(envelope_data)
                        except (json.JSONDecodeError, KeyError, ValueError) as exc:
                            logger.warning("Failed to deserialize event from %s: %s", msg_id, exc)
                            # Move unparseable messages to DLQ
                            self._move_to_dlq(client, stream_key, msg_id, envelope_json)
                            client.xack(stream_key, group, msg_id)
                            continue

                        try:
                            if self._already_processed(envelope):
                                client.xack(stream_key, group, msg_id)
                                continue
                            handler(envelope)
                            self._mark_processed(envelope)
                            client.xack(stream_key, group, msg_id)
                        except TransientHandlerError:
                            # Do not XACK; let XAUTOCLAIM retry
                            logger.info("Transient error processing %s, leaving in PEL", msg_id)
                        except FatalHandlerError:
                            # Move to DLQ and XACK
                            self._move_to_dlq(client, stream_key, msg_id, envelope_json)
                            client.xack(stream_key, group, msg_id)
                        except Exception as exc:
                            logger.exception("Unexpected error processing %s: %s", msg_id, exc)
                            # Treat as transient
                            pass
            except _redis.RedisError as exc:
                logger.error("Redis error in polling loop: %s", exc)
                if not self._stop_event.is_set():
                    time.sleep(1)

    def _recovery_loop(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        client = self._get_client()
        import redis as _redis  # type: ignore[import-untyped]

        while not self._stop_event.is_set():
            time.sleep(60)
            if self._stop_event.is_set():
                return

            for stream_key in streams:
                try:
                    claimed = client.xautoclaim(
                        stream_key,
                        group,
                        consumer_name,
                        min_idle_time=60000,
                        start_id="0",
                        count=100,
                    )
                    # xautoclaim returns (next_start_id, [messages...]) in redis-py >= 5
                    if isinstance(claimed, (list, tuple)) and len(claimed) >= 2:
                        messages = claimed[1]
                    else:
                        messages = claimed or []

                    for msg_id, msg_data in messages:
                        delivery_count = self._get_delivery_count(client, stream_key, group, msg_id)
                        envelope_json = msg_data.get("envelope", "{}")

                        if delivery_count >= 5:
                            self._move_to_dlq(client, stream_key, msg_id, envelope_json)
                            client.xack(stream_key, group, msg_id)
                            continue

                        try:
                            envelope_data = json.loads(envelope_json)
                            envelope = EventEnvelope.from_json_dict(envelope_data)
                            if self._already_processed(envelope):
                                client.xack(stream_key, group, msg_id)
                                continue
                            handler(envelope)
                            self._mark_processed(envelope)
                            client.xack(stream_key, group, msg_id)
                        except (json.JSONDecodeError, KeyError, ValueError):
                            self._move_to_dlq(client, stream_key, msg_id, envelope_json)
                            client.xack(stream_key, group, msg_id)
                        except TransientHandlerError:
                            pass
                        except FatalHandlerError:
                            self._move_to_dlq(client, stream_key, msg_id, envelope_json)
                            client.xack(stream_key, group, msg_id)
                        except Exception:
                            pass
                except _redis.RedisError as exc:
                    logger.error("Redis error in recovery loop: %s", exc)

    def _already_processed(self, envelope: EventEnvelope) -> bool:
        if envelope.event_id not in self._seen_event_ids:
            return False
        self._seen_event_ids.move_to_end(envelope.event_id)
        return True

    def _mark_processed(self, envelope: EventEnvelope) -> None:
        self._seen_event_ids[envelope.event_id] = None
        self._seen_event_ids.move_to_end(envelope.event_id)
        while len(self._seen_event_ids) > self._dedup_max_entries:
            self._seen_event_ids.popitem(last=False)

    def _get_delivery_count(
        self,
        client: Any,
        stream_key: str,
        group: str,
        msg_id: str,
    ) -> int:
        """Get delivery count from the PEL entry."""
        import redis as _redis  # type: ignore[import-untyped]

        try:
            pending = client.xpending_range(stream_key, group, min=msg_id, max=msg_id, count=1)
            if pending:
                return pending[0].get("times_delivered", 1)
        except _redis.RedisError:
            pass
        return 1

    def _move_to_dlq(
        self,
        client: Any,
        stream_key: str,
        msg_id: str,
        envelope_json: str,
    ) -> None:
        """Move a poison message to the dead-letter stream."""
        dlq_key = f"{stream_key}:dlq"
        try:
            client.xadd(dlq_key, {"envelope": envelope_json}, maxlen=100000, approximate=True)
        except Exception as exc:
            logger.error("Failed to move message %s to DLQ: %s", msg_id, exc)

    def stop(self) -> None:
        """Graceful shutdown."""
        self._stop_event.set()
        for thread in self._threads:
            thread.join(timeout=5)
