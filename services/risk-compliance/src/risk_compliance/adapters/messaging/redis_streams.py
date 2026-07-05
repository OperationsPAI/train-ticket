from __future__ import annotations

import json
import os
import time
from collections.abc import Mapping
from typing import Any

import redis

from ...application import (
    EventEnvelope,
    EventHandler,
    FatalHandlerError,
    PublishFailed,
    SubscribeFailed,
    TransientHandlerError,
)

_STREAM_PREFIX = "events:"
_ENVELOPE_FIELD = "envelope"
_MAXLEN = 100_000
_MAX_ATTEMPTS = 5
_MIN_IDLE_MS = 60_000
_BLOCK_MS = 2_000
_READ_COUNT = 10


def redis_url_from_env() -> str:
    return os.getenv("REDIS_URL", "redis://localhost:6379")


def _stream_for_producer(producer: str) -> str:
    return f"{_STREAM_PREFIX}{producer}"


def _dlq_for_stream(stream: str) -> str:
    return f"{stream}:dlq"


class RedisEventPublisher:
    def __init__(self, url: str | None = None, client: Any | None = None) -> None:
        self._client = client or redis.Redis.from_url(url or redis_url_from_env(), decode_responses=True)

    def publish(self, envelope: EventEnvelope) -> None:
        stream = _stream_for_producer(envelope.producer)
        payload = json.dumps(envelope.to_dict(), separators=(",", ":"), sort_keys=True)
        last_error: BaseException | None = None
        for attempt in range(3):
            try:
                self._client.xadd(stream, {_ENVELOPE_FIELD: payload}, maxlen=_MAXLEN, approximate=True)
                return
            except redis.RedisError as exc:
                last_error = exc
                if attempt < 2:
                    time.sleep(0.05 * (2**attempt))
        raise PublishFailed("event was not published") from last_error


class RedisEventSubscriber:
    def __init__(self, url: str | None = None, client: Any | None = None, *, poll_once: bool = False) -> None:
        self._client = client or redis.Redis.from_url(url or redis_url_from_env(), decode_responses=True)
        self._running = False
        self._seen_event_ids: set[str] = set()
        self._poll_once = poll_once

    def stop(self) -> None:
        self._running = False

    def subscribe(
        self,
        streams: list[str],
        group: str,
        consumerName: str,
        handler: EventHandler,
    ) -> None:
        try:
            for stream in streams:
                self._ensure_group(stream, group)
            self._running = True
            while self._running:
                self._recover_pending(streams, group, consumerName, handler)
                response = self._client.xreadgroup(
                    group,
                    consumerName,
                    {stream: ">" for stream in streams},
                    count=_READ_COUNT,
                    block=_BLOCK_MS,
                )
                self._handle_response(response, group, handler)
                if self._poll_once:
                    self._running = False
        except redis.RedisError as exc:
            raise SubscribeFailed("subscriber failed") from exc

    def _ensure_group(self, stream: str, group: str) -> None:
        try:
            self._client.xgroup_create(stream, group, id="$", mkstream=True)
        except redis.ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def _recover_pending(
        self,
        streams: list[str],
        group: str,
        consumer_name: str,
        handler: EventHandler,
    ) -> None:
        for stream in streams:
            claimed = self._client.xautoclaim(stream, group, consumer_name, _MIN_IDLE_MS, "0", count=100)
            messages = claimed[1] if len(claimed) > 1 else []
            self._handle_stream_messages(stream, messages, group, handler)

    def _handle_response(self, response: list[Any], group: str, handler: EventHandler) -> None:
        for stream, messages in response or []:
            self._handle_stream_messages(stream, messages, group, handler)

    def _handle_stream_messages(self, stream: str, messages: list[Any], group: str, handler: EventHandler) -> None:
        for entry_id, fields in messages:
            envelope_json = _field_value(fields, _ENVELOPE_FIELD)
            if envelope_json is None:
                self._move_to_dlq(stream, entry_id, group, fields)
                continue
            if self._delivery_count(stream, group, entry_id) >= _MAX_ATTEMPTS:
                self._move_to_dlq(stream, entry_id, group, fields)
                continue
            try:
                envelope = EventEnvelope.from_mapping(json.loads(envelope_json))
                if envelope.eventId in self._seen_event_ids:
                    self._client.xack(stream, group, entry_id)
                    continue
                handler(envelope)
                self._seen_event_ids.add(envelope.eventId)
                self._client.xack(stream, group, entry_id)
            except TransientHandlerError:
                continue
            except (FatalHandlerError, json.JSONDecodeError, KeyError, TypeError, ValueError):
                self._move_to_dlq(stream, entry_id, group, fields)

    def _delivery_count(self, stream: str, group: str, entry_id: str) -> int:
        try:
            pending = self._client.xpending_range(stream, group, entry_id, entry_id, 1)
        except redis.RedisError:
            return 1
        if not pending:
            return 1
        value = pending[0]
        if isinstance(value, Mapping):
            return int(value.get("times_delivered") or value.get("delivery_count") or 1)
        return int(value[3]) if len(value) > 3 else 1

    def _move_to_dlq(self, stream: str, entry_id: str, group: str, fields: Mapping[str, Any]) -> None:
        self._client.xadd(_dlq_for_stream(stream), dict(fields), maxlen=_MAXLEN, approximate=True)
        self._client.xack(stream, group, entry_id)


def _field_value(fields: Mapping[str, Any], key: str) -> str | None:
    value = fields.get(key)
    if value is None:
        value = fields.get(key.encode("utf-8"))
    if isinstance(value, bytes):
        return value.decode("utf-8")
    if value is None:
        return None
    return str(value)
