from __future__ import annotations

import json
import logging
import threading
import time
from typing import Any, Callable

from trip_planning.events import (
    EventEnvelope,
    EventSubscriber,
    FatalHandlerError,
    SubscribeFailed,
    TransientHandlerError,
)

logger = logging.getLogger(__name__)

MAXLEN = 100000
DLQ_STREAM_TEMPLATE = "{stream}:dlq"
MAX_DELIVERY_ATTEMPTS = 5
CLAIM_MIN_IDLE_MS = 60000
CLAIM_COUNT = 100
POLL_BLOCK_MS = 2000
POLL_COUNT = 10


class RedisEventSubscriber(EventSubscriber):
    """Redis Streams implementation of EventSubscriber.

    Lives ONLY under adapters/messaging — no Redis types outside this module.
    """

    def __init__(self, redis_client: Any) -> None:
        """Initialize with a redis-py Redis client.

        Args:
            redis_client: A redis.Redis (or StrictRedis) instance.
        """
        self._redis = redis_client
        self._running = False
        self._threads: list[threading.Thread] = []
        self._dedup: set[str] = set()
        self._dedup_lock = threading.Lock()

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        """Start background polling threads for each stream.

        This method creates threads and returns immediately. The caller
        must call stop() for graceful shutdown.

        Raises SubscribeFailed if group creation fails.
        """
        self._running = True

        for stream in streams:
            try:
                self._redis.xgroup_create(stream, group, id="$", mkstream=True)
            except Exception as exc:
                # Redis replies with BUSYGROUP if group already exists — safe to ignore
                if "BUSYGROUP" not in str(exc):
                    self._running = False
                    raise SubscribeFailed(
                        f"Failed to create consumer group {group} on {stream}: {exc}"
                    ) from exc

        for stream in streams:
            thread = threading.Thread(
                target=self._poll_loop,
                args=(stream, group, consumer_name, handler),
                daemon=True,
            )
            thread.start()
            self._threads.append(thread)

            # Start a recovery thread for this stream
            recovery_thread = threading.Thread(
                target=self._recovery_loop,
                args=(stream, group, consumer_name, handler),
                daemon=True,
            )
            recovery_thread.start()
            self._threads.append(recovery_thread)

    def stop(self) -> None:
        """Signal all polling threads to stop."""
        self._running = False

    def _dedup_check(self, event_id: str) -> bool:
        """Check if an eventId has already been processed.

        Returns True if the event should be skipped (already seen).
        """
        with self._dedup_lock:
            if event_id in self._dedup:
                return True
            self._dedup.add(event_id)
            # Keep dedup set bounded; trim if too large
            if len(self._dedup) > 100000:
                self._dedup.clear()
        return False

    def _poll_loop(
        self,
        stream: str,
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        """Continuously poll the stream for new messages."""
        while self._running:
            try:
                results = self._redis.xreadgroup(
                    group,
                    consumer_name,
                    {stream: ">"},
                    count=POLL_COUNT,
                    block=POLL_BLOCK_MS,
                )
                if not results:
                    continue

                for stream_name, messages in results:
                    for msg_id, msg_data in messages:
                        self._process_message(stream_name, group, msg_id, msg_data, handler)
            except Exception as exc:
                logger.error("poll loop error on %s: %s", stream, exc)
                if self._running:
                    time.sleep(1)

    def _recovery_loop(
        self,
        stream: str,
        group: str,
        consumer_name: str,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        """Periodically reclaim stale pending messages via XAUTOCLAIM."""
        while self._running:
            try:
                claimed = self._redis.xautoclaim(
                    stream,
                    group,
                    consumer_name,
                    CLAIM_MIN_IDLE_MS,
                    "0",
                    count=CLAIM_COUNT,
                )
                # claimed format: (next_start_id, [msg_id, msg_data, ...], [])
                if claimed and len(claimed) >= 2:
                    messages = claimed[1] if isinstance(claimed[1], list) else []
                    for msg_id, msg_data in messages:
                        self._process_message(stream, group, msg_id, msg_data, handler)
            except Exception as exc:
                logger.error("recovery loop error on %s: %s", stream, exc)

            # Sleep 60 seconds between recovery cycles
            for _ in range(60):
                if not self._running:
                    break
                time.sleep(1)

    def _process_message(
        self,
        stream: str,
        group: str,
        msg_id: bytes | str,
        msg_data: dict[str, Any] | list,
        handler: Callable[[EventEnvelope], None],
    ) -> None:
        """Process a single message from the stream.

        Handles dedup, delivery, ack, and DLQ logic.
        """
        msg_id_str = msg_id.decode() if isinstance(msg_id, bytes) else str(msg_id)

        try:
            envelope_raw = msg_data.get(b"envelope") or msg_data.get("envelope", "")
            if isinstance(envelope_raw, bytes):
                envelope_raw = envelope_raw.decode("utf-8")
            envelope_dict = json.loads(envelope_raw)
        except (json.JSONDecodeError, KeyError, TypeError) as exc:
            logger.error("failed to parse envelope from %s/%s: %s", stream, msg_id_str, exc)
            # Move malformed messages to DLQ immediately
            self._move_to_dlq(stream, envelope_raw if isinstance(envelope_raw, str) else "{}")
            self._xack(stream, group, msg_id_str)
            return

        try:
            envelope = EventEnvelope.from_json_dict(envelope_dict)
        except (KeyError, ValueError) as exc:
            logger.error("invalid envelope schema from %s/%s: %s", stream, msg_id_str, exc)
            self._move_to_dlq(stream, envelope_raw if isinstance(envelope_raw, str) else "{}")
            self._xack(stream, group, msg_id_str)
            return

        # Dedup check
        if self._dedup_check(envelope.eventId):
            # Already processed; ack and skip
            self._xack(stream, group, msg_id_str)
            return

        # Check delivery count
        delivery_count = self._get_delivery_count(stream, group, msg_id_str)
        if delivery_count >= MAX_DELIVERY_ATTEMPTS:
            logger.warning(
                "message %s/%s exceeded max delivery attempts (%d); moving to DLQ",
                stream,
                msg_id_str,
                MAX_DELIVERY_ATTEMPTS,
            )
            self._move_to_dlq(stream, envelope_raw if isinstance(envelope_raw, str) else json.dumps(envelope_dict))
            self._xack(stream, group, msg_id_str)
            return

        try:
            handler(envelope)
            self._xack(stream, group, msg_id_str)
        except TransientHandlerError:
            # Leave in PEL for retry
            logger.info("transient error for %s/%s; leaving in PEL", stream, msg_id_str)
        except FatalHandlerError:
            logger.warning(
                "fatal error for %s/%s; moving to DLQ", stream, msg_id_str
            )
            self._move_to_dlq(stream, envelope_raw if isinstance(envelope_raw, str) else json.dumps(envelope_dict))
            self._xack(stream, group, msg_id_str)
        except Exception as exc:
            logger.error("unhandled error for %s/%s: %s", stream, msg_id_str, exc)
            # Treat unhandled exceptions as transient
            raise

    def _xack(self, stream: str, group: str, msg_id: str) -> None:
        """Acknowledge a message."""
        try:
            self._redis.xack(stream, group, msg_id)
        except Exception as exc:
            logger.error("xack failed for %s/%s: %s", stream, msg_id, exc)

    def _move_to_dlq(self, stream: str, envelope_json: str) -> None:
        """Move a message to the dead-letter stream."""
        dlq_stream = DLQ_STREAM_TEMPLATE.format(stream=stream)
        try:
            self._redis.xadd(
                dlq_stream,
                {"envelope": envelope_json},
                maxlen=MAXLEN,
                approximate=True,
            )
        except Exception as exc:
            logger.error("failed to move message to DLQ %s: %s", dlq_stream, exc)

    def _get_delivery_count(self, stream: str, group: str, msg_id: str) -> int:
        """Get the delivery count for a pending message."""
        try:
            # XPENDING with detail returns delivery count
            info = self._redis.xpending_range(stream, group, min=msg_id, max=msg_id, count=1)
            if info:
                return info[0].get("times_delivered", 0) if hasattr(info[0], "get") else 0
        except Exception:
            pass
        return 0
