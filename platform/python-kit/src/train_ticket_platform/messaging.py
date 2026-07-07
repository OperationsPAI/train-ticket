from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from datetime import datetime, timezone
import json
import logging
import os
import socket
import threading
import time
from typing import Any, Protocol

from .events import EventEnvelope

MAXLEN = 100000
MAX_RETRIES = 3
RETRY_BACKOFF_SECONDS = (0.1, 0.3, 0.9)
MAX_DELIVERY_ATTEMPTS = 5
CLAIM_MIN_IDLE_MS = 60000
CLAIM_COUNT = 100
POLL_BLOCK_MS = 2000
POLL_COUNT = 10
_LOG = logging.getLogger(__name__)


class PublishFailed(RuntimeError):
    pass


class SubscribeFailed(RuntimeError):
    pass


class HandlerError(RuntimeError):
    pass


class TransientHandlerError(HandlerError):
    pass


class FatalHandlerError(HandlerError):
    pass


class HandlerStatus(str, Enum):
    SUCCESS = "SUCCESS"
    TRANSIENT_ERROR = "TRANSIENT_ERROR"
    FATAL_ERROR = "FATAL_ERROR"


@dataclass(frozen=True, slots=True)
class HandlerResult:
    status: HandlerStatus
    message: str = ""

    @classmethod
    def success(cls) -> "HandlerResult":
        return cls(HandlerStatus.SUCCESS)

    @classmethod
    def transient_error(cls, message: str = "") -> "HandlerResult":
        return cls(HandlerStatus.TRANSIENT_ERROR, message)

    @classmethod
    def fatal_error(cls, message: str = "") -> "HandlerResult":
        return cls(HandlerStatus.FATAL_ERROR, message)


class EventPublisher(Protocol):
    def publish(self, envelope: EventEnvelope) -> None: ...


class EventSubscriber(Protocol):
    def subscribe(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None: ...


def stream_for_producer(producer: str) -> str:
    return f"events:{producer}"


def dlq_for_stream(stream: str) -> str:
    return f"{stream}:dlq"


def default_consumer_name(group: str) -> str:
    instance = os.getenv("HOSTNAME") or socket.gethostname() or "local"
    safe_instance = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in instance)
    return f"{group}-{safe_instance}"


class RedisEventPublisher(EventPublisher):
    def __init__(self, redis_client: Any | None = None, redis_url: str | None = None) -> None:
        if redis_client is not None:
            self._redis = redis_client
            self._client = self._redis
        else:
            try:
                import redis
            except ImportError as exc:  # pragma: no cover
                raise PublishFailed("redis-py is not installed") from exc
            self._redis = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)
            self._client = self._redis

    def publish(self, envelope: EventEnvelope) -> None:
        stream_key = stream_for_producer(envelope.producer)
        payload = json.dumps(envelope.to_json_dict(), ensure_ascii=False, separators=(",", ":"))
        last_error: Exception | None = None
        for attempt in range(MAX_RETRIES):
            try:
                self._client.xadd(stream_key, {"envelope": payload}, maxlen=MAXLEN, approximate=True)
                return
            except Exception as exc:
                last_error = exc
                if attempt < MAX_RETRIES - 1:
                    time.sleep(RETRY_BACKOFF_SECONDS[attempt])
        raise PublishFailed(f"Failed to publish event {envelope.eventId} after {MAX_RETRIES} attempts") from last_error


class RedisEventSubscriber(EventSubscriber):
    def __init__(self, redis_client: Any | None = None, redis_url: str | None = None) -> None:
        if redis_client is not None:
            self._redis = redis_client
            self._client = self._redis
            self._response_error_type = Exception
        else:
            try:
                import redis
            except ImportError as exc:  # pragma: no cover
                raise SubscribeFailed("redis-py is not installed") from exc
            self._redis = redis.Redis.from_url(redis_url or os.getenv("REDIS_URL", "redis://localhost:6379"), decode_responses=True)
            self._client = self._redis
            self._response_error_type = redis.exceptions.ResponseError
        self._dedup: set[str] = set()
        self._seen_event_ids = self._dedup
        self._dedup_lock = threading.Lock()
        self._seen_event_ids_lock = self._dedup_lock
        self._stop_requested = threading.Event()
        self._threads: list[threading.Thread] = []

    def subscribe(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        streams_tuple = tuple(streams)
        if not streams_tuple:
            return
        try:
            for stream in streams_tuple:
                self._ensure_group(stream, group)
        except Exception as exc:
            raise SubscribeFailed("subscriber could not start Redis Streams consumer group") from exc
        next_recovery_at = 0.0
        self._stop_requested.clear()
        while not self._stop_requested.is_set():
            try:
                now = time.monotonic()
                if now >= next_recovery_at:
                    self._recover_pending(streams_tuple, group, consumer_name, handler)
                    next_recovery_at = now + (CLAIM_MIN_IDLE_MS / 1000)
                results = self._client.xreadgroup(
                    group,
                    consumer_name,
                    {stream: ">" for stream in streams_tuple},
                    count=POLL_COUNT,
                    block=POLL_BLOCK_MS,
                )
                self._process_results(results, group, consumer_name, handler)
            except Exception as exc:
                if self._stop_requested.is_set():
                    break
                if isinstance(exc, (TransientHandlerError, FatalHandlerError)):
                    raise
                # Redis is non-persistent here: a restart drops consumer groups,
                # so recreate them on NOGROUP instead of spinning on the error.
                if "NOGROUP" in str(exc):
                    for stream in streams_tuple:
                        self._ensure_group(stream, group)
                # Back off so a dead connection never hot-spins.
                time.sleep(1)

    def start_in_background(
        self,
        streams: Sequence[str] | Callable[[EventEnvelope], Any],
        group: str | None = None,
        handler: Callable[[EventEnvelope], Any] | None = None,
        *,
        consumer_name: str | None = None,
    ) -> threading.Thread:
        if callable(streams) and handler is None:
            handler = streams
            group = group or "default"
            streams = ()
        if handler is None or group is None:
            raise SubscribeFailed("streams, group, and handler are required")
        thread = threading.Thread(
            target=self.subscribe,
            args=(tuple(streams), group, consumer_name or default_consumer_name(group), handler),
            daemon=True,
            name=f"{group}-redis-subscriber",
        )
        thread.start()
        self._threads.append(thread)
        return thread

    def stop(self) -> None:
        self._stop_requested.set()
        for thread in list(self._threads):
            if thread is not threading.current_thread():
                thread.join(timeout=5)

    def shutdown(self) -> None:
        self.stop()

    def _ensure_group(self, stream: str, group: str) -> None:
        try:
            self._client.xgroup_create(stream, group, id="$", mkstream=True)
        except Exception as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def _recover_pending(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        for stream in streams:
            claimed = self._client.xautoclaim(stream, group, consumer_name, CLAIM_MIN_IDLE_MS, "0", count=CLAIM_COUNT)
            messages = claimed[1] if claimed and len(claimed) > 1 else []
            for msg_id, msg_data in messages:
                self._process_message(stream, group, consumer_name, msg_id, msg_data, handler)

    def _process_results(self, results: Any, group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        for stream_name, messages in results or []:
            stream = stream_name.decode("utf-8") if isinstance(stream_name, bytes) else str(stream_name)
            for msg_id, msg_data in messages:
                self._process_message(stream, group, consumer_name, msg_id, msg_data, handler)

    def _process_message(self, stream: str, group: str, consumer_name: str, msg_id: bytes | str, msg_data: Mapping[Any, Any], handler: Callable[[EventEnvelope], Any]) -> None:
        msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
        envelope_json = self._extract_envelope_json(msg_data)
        try:
            envelope = self._deserialize_envelope(json.loads(envelope_json))
        except Exception as exc:
            self._move_to_dlq(stream, envelope_json or "{}", group, consumer_name, _failure_reason(exc), self._delivery_count(stream, group, msg_id_str))
            self._xack(stream, group, msg_id_str)
            return
        if self._already_seen(envelope.eventId):
            self._xack(stream, group, msg_id_str)
            return
        delivery_count = self._delivery_count(stream, group, msg_id_str)
        if delivery_count >= MAX_DELIVERY_ATTEMPTS:
            self._move_to_dlq(stream, envelope_json, group, consumer_name, "delivery attempts exhausted", delivery_count)
            self._xack(stream, group, msg_id_str)
            return
        try:
            result = handler(envelope)
        except TransientHandlerError:
            return
        except FatalHandlerError as exc:
            self._move_to_dlq(stream, envelope_json, group, consumer_name, _failure_reason(exc), self._delivery_count(stream, group, msg_id_str))
            self._xack(stream, group, msg_id_str)
            return
        except Exception as exc:
            delivery_count = self._delivery_count(stream, group, msg_id_str)
            if delivery_count >= MAX_DELIVERY_ATTEMPTS:
                self._move_to_dlq(stream, envelope_json, group, consumer_name, _failure_reason(exc), delivery_count)
                self._xack(stream, group, msg_id_str)
            return
        if isinstance(result, HandlerResult):
            if result.status is HandlerStatus.TRANSIENT_ERROR:
                return
            if result.status is HandlerStatus.FATAL_ERROR:
                self._move_to_dlq(stream, envelope_json, group, consumer_name, result.message or "handler returned FATAL_ERROR", self._delivery_count(stream, group, msg_id_str))
                self._xack(stream, group, msg_id_str)
                return
        self._record_seen(envelope.eventId)
        self._xack(stream, group, msg_id_str)

    def _extract_envelope_json(self, msg_data: Mapping[Any, Any]) -> str:
        envelope = msg_data.get(b"envelope") if hasattr(msg_data, "get") else None
        if envelope is None and hasattr(msg_data, "get"):
            envelope = msg_data.get("envelope")
        if envelope is None and hasattr(msg_data, "get"):
            envelope = msg_data.get(b"d")
        if envelope is None and hasattr(msg_data, "get"):
            envelope = msg_data.get("d")
        if isinstance(envelope, bytes):
            return envelope.decode("utf-8")
        if isinstance(envelope, str):
            return envelope
        return ""

    def _delivery_count(self, stream: str, group: str, msg_id: str) -> int:
        try:
            pending = self._client.xpending_range(stream, group, min=msg_id, max=msg_id, count=1)
        except Exception:
            return 0
        if not pending:
            return 0
        first = pending[0]
        if hasattr(first, "get"):
            return int(first.get("times_delivered", first.get(b"times_delivered", first.get("timesDelivered", 0))))
        return 0

    def _already_seen(self, event_id: str) -> bool:
        lock = getattr(self, "_dedup_lock", getattr(self, "_seen_event_ids_lock", None))
        seen = getattr(self, "_dedup", getattr(self, "_seen_event_ids", set()))
        if lock is None:
            return event_id in seen
        with lock:
            return event_id in seen

    def _record_seen(self, event_id: str) -> None:
        lock = getattr(self, "_dedup_lock", getattr(self, "_seen_event_ids_lock", None))
        seen = getattr(self, "_dedup", getattr(self, "_seen_event_ids", None))
        if seen is None:
            seen = set()
            setattr(self, "_dedup", seen)
            setattr(self, "_seen_event_ids", seen)
        if lock is None:
            seen.add(event_id)
            return
        with lock:
            seen.add(event_id)

    def _move_to_dlq(self, stream: str, envelope_json: str, consumer_group: str, consumer_name: str, failure_reason: str, attempts: int) -> None:
        fields = _dead_letter_fields(envelope_json, consumer_group, consumer_name, failure_reason, attempts)
        self._client.xadd(dlq_for_stream(stream), fields, maxlen=MAXLEN, approximate=True)
        _LOG.warning(
            "dead-lettered event service=%s stream=%s eventId=%s reason=%s",
            consumer_group,
            stream,
            _event_id_from(envelope_json),
            fields["failureReason"],
        )

    def _xack(self, stream: str, group: str, msg_id: str) -> None:
        self._client.xack(stream, group, msg_id)

    @staticmethod
    def _deserialize_envelope(raw: Mapping[str, Any]) -> EventEnvelope:
        return EventEnvelope.from_json_dict(raw)

    def _process_entry(
        self,
        stream: str,
        group: str,
        entry_id: str,
        fields: Mapping[Any, Any],
        handler: Callable[[EventEnvelope], Any],
        delivery_count: int = 0,
    ) -> None:
        if delivery_count >= MAX_DELIVERY_ATTEMPTS:
            envelope_json = self._extract_envelope_json(fields)
            self._move_to_dlq(stream, envelope_json or "{}", group, "unknown", "delivery attempts exhausted", delivery_count)
            self._xack(stream, group, entry_id)
            return
        self._process_message(stream, group, "unknown", entry_id, fields, handler)


def _dead_letter_fields(envelope_json: str, consumer_group: str, consumer_name: str, failure_reason: str, attempts: int) -> dict[str, str]:
    return {
        "envelope": envelope_json,
        "consumerGroup": consumer_group or "unknown",
        "consumerName": consumer_name or "unknown",
        "failureReason": _truncate_failure_reason(failure_reason or "unknown"),
        "attempts": str(max(1, attempts)),
        "deadLetteredAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
    }


def _failure_reason(exc: BaseException) -> str:
    message = str(exc)
    return _truncate_failure_reason(f"{exc.__class__.__name__}{': ' + message if message else ''}")


def _truncate_failure_reason(reason: str) -> str:
    return reason[:500]


def _event_id_from(envelope_json: str) -> str:
    try:
        event_id = json.loads(envelope_json).get("eventId")
    except Exception:
        return "unknown"
    return event_id if isinstance(event_id, str) and event_id else "unknown"


class InMemoryEventPublisher(EventPublisher):
    def __init__(self) -> None:
        self.envelopes: list[EventEnvelope] = []
        self._fail_next = False

    def publish(self, envelope: EventEnvelope) -> None:
        if self._fail_next:
            self._fail_next = False
            raise PublishFailed("configured publish failure")
        self.envelopes.append(envelope)

    def fail_next_publish(self) -> None:
        self._fail_next = True


class InMemoryEventSubscriber(EventSubscriber):
    def __init__(self, envelopes: Sequence[EventEnvelope] | None = None) -> None:
        self.envelopes = list(envelopes or [])
        self.seen_event_ids: set[str] = set()

    def subscribe(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        del streams, group, consumer_name
        for envelope in self.envelopes:
            if envelope.eventId in self.seen_event_ids:
                continue
            result = handler(envelope)
            if not isinstance(result, HandlerResult) or result.status is HandlerStatus.SUCCESS:
                self.seen_event_ids.add(envelope.eventId)

    def stop(self) -> None:
        return None
