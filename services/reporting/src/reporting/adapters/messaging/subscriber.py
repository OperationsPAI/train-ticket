from __future__ import annotations

import json
import threading
import time
from collections.abc import Sequence
from typing import Any

from reporting.application.ports import EventEnvelope, EventHandler, EventSubscriber, HandlerStatus, SubscribeFailed

from .stream_config import BLOCK_MS, MIN_IDLE_MS, READ_COUNT, RETRY_LIMIT, STREAM_MAXLEN, dlq_for_stream


class RedisEventSubscriber(EventSubscriber):
    def __init__(self, redis_url: str) -> None:
        try:
            from redis import Redis
            from redis.exceptions import ResponseError
        except ImportError as exc:  # pragma: no cover - depends on deployment deps
            raise SubscribeFailed("redis-py is required for RedisEventSubscriber") from exc
        self._client = Redis.from_url(redis_url, decode_responses=True)
        self._response_error_type = ResponseError
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self._seen_event_ids: set[str] = set()
        self._seen_event_ids_lock = threading.Lock()

    def subscribe(self, streams: Sequence[str], group: str, consumer_name: str, handler: EventHandler) -> None:
        if not streams:
            return
        try:
            for stream in streams:
                self._ensure_group(stream, group)
        except Exception as exc:
            raise SubscribeFailed("failed to create reporting consumer groups") from exc
        thread = threading.Thread(
            target=self._poll_loop,
            args=(tuple(streams), group, consumer_name, handler),
            name=f"{consumer_name}-redis-streams",
            daemon=True,
        )
        thread.start()
        self._threads.append(thread)

    def stop(self) -> None:
        self._stop.set()
        for thread in self._threads:
            thread.join(timeout=5)

    def _ensure_group(self, stream: str, group: str) -> None:
        try:
            self._client.xgroup_create(stream, group, id="$", mkstream=True)
        except self._response_error_type as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def _poll_loop(self, streams: Sequence[str], group: str, consumer_name: str, handler: EventHandler) -> None:
        last_recovery = 0.0
        while not self._stop.is_set():
            stream_offsets = {stream: ">" for stream in streams}
            messages = self._client.xreadgroup(
                group,
                consumer_name,
                stream_offsets,
                count=READ_COUNT,
                block=BLOCK_MS,
            )
            self._handle_messages(messages, group, handler)
            now = time.monotonic()
            if now - last_recovery >= 60:
                for stream in streams:
                    self._recover_pending(stream, group, consumer_name, handler)
                last_recovery = now

    def _handle_messages(self, messages: Any, group: str, handler: EventHandler) -> None:
        for stream, entries in messages or []:
            for entry_id, fields in entries:
                self._process_entry(stream, group, entry_id, fields, handler, delivery_count=1)

    def _recover_pending(self, stream: str, group: str, consumer_name: str, handler: EventHandler) -> None:
        claimed = self._client.xautoclaim(stream, group, consumer_name, MIN_IDLE_MS, "0", count=100)
        entries = claimed[1] if isinstance(claimed, (list, tuple)) and len(claimed) > 1 else []
        delivery_counts = self._delivery_counts(stream, group)
        for entry_id, fields in entries:
            self._process_entry(stream, group, entry_id, fields, handler, delivery_counts.get(entry_id, 1))

    def _delivery_counts(self, stream: str, group: str) -> dict[str, int]:
        counts: dict[str, int] = {}
        try:
            pending = self._client.xpending_range(stream, group, "-", "+", 100)
        except Exception:
            return counts
        for item in pending:
            message_id = item.get("message_id") or item.get("messageId")
            times_delivered = item.get("times_delivered") or item.get("timesDelivered")
            if message_id is not None and times_delivered is not None:
                counts[str(message_id)] = int(times_delivered)
        return counts

    def _process_entry(
        self,
        stream: str,
        group: str,
        entry_id: str,
        fields: dict[str, str],
        handler: EventHandler,
        delivery_count: int,
    ) -> None:
        envelope_json = fields.get("envelope")
        if not envelope_json:
            self._move_to_dlq(stream, fields)
            self._client.xack(stream, group, entry_id)
            return
        if delivery_count >= RETRY_LIMIT:
            self._move_to_dlq(stream, {"envelope": envelope_json})
            self._client.xack(stream, group, entry_id)
            return
        try:
            raw = json.loads(envelope_json)
            envelope = self._deserialize_envelope(raw)
            if self._already_seen(envelope.eventId):
                self._client.xack(stream, group, entry_id)
                return
            result = handler(envelope)
        except Exception:
            self._move_to_dlq(stream, {"envelope": envelope_json})
            self._client.xack(stream, group, entry_id)
            return
        if result.status is HandlerStatus.SUCCESS:
            self._record_seen(envelope.eventId)
            self._client.xack(stream, group, entry_id)
        elif result.status is HandlerStatus.FATAL_ERROR:
            self._move_to_dlq(stream, {"envelope": envelope_json})
            self._client.xack(stream, group, entry_id)

    @staticmethod
    def _deserialize_envelope(raw: dict[str, Any]) -> EventEnvelope:
        return EventEnvelope(
            eventId=raw["eventId"],
            eventType=raw["eventType"],
            occurredAt=raw["occurredAt"],
            correlationId=raw["correlationId"],
            producer=raw["producer"],
            schemaVersion=raw["schemaVersion"],
            payload=raw["payload"],
            causationId=raw.get("causationId"),
        )

    def _already_seen(self, event_id: str) -> bool:
        with self._seen_event_ids_lock:
            return event_id in self._seen_event_ids

    def _record_seen(self, event_id: str) -> None:
        with self._seen_event_ids_lock:
            self._seen_event_ids.add(event_id)

    def _move_to_dlq(self, stream: str, fields: dict[str, str]) -> None:
        self._client.xadd(dlq_for_stream(stream), fields, maxlen=STREAM_MAXLEN, approximate=True)
