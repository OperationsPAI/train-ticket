from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from datetime import UTC, datetime
import json
import logging
import os
import socket
import threading
import time
from typing import Any, Protocol

from .events import EventEnvelope
from .observability import otel_tracing_enabled


STREAM_MAXLEN_ENV = "EVENT_STREAM_MAXLEN"
DEFAULT_STREAM_MAXLEN = 10000
MAX_RETRIES = 3
RETRY_BACKOFF_SECONDS = (0.1, 0.3, 0.9)
MAX_DELIVERY_ATTEMPTS = 5
CLAIM_MIN_IDLE_MS = 60000
CLAIM_COUNT = 100
DEAD_CONSUMER_IDLE_MS = 5 * 60 * 1000
POLL_BLOCK_MS = int(os.environ.get("CONSUMER_BLOCK_MS", "100"))
POLL_COUNT = int(os.environ.get("CONSUMER_BATCH_COUNT", "100"))
LOGGER = logging.getLogger(__name__)


def stream_maxlen(configured: str | None) -> int:
    """Resolve the per-stream entry cap, falling back to the default when misconfigured.

    A blank, non-numeric or non-positive value falls back to the default rather than
    raising or trimming to zero: silently discarding every published event would be
    far worse than ignoring the misconfiguration.
    """
    if configured is None or not configured.strip():
        return DEFAULT_STREAM_MAXLEN
    try:
        maxlen = int(configured.strip())
        if maxlen > 0:
            return maxlen
    except ValueError:
        pass
    LOGGER.warning(
        "%s=%s is not a positive integer; using default %s",
        STREAM_MAXLEN_ENV,
        configured,
        DEFAULT_STREAM_MAXLEN,
    )
    return DEFAULT_STREAM_MAXLEN


# Cap on entries kept per stream. Redis streams are never read destructively, so without
# a cap every published event stays resident forever and eventually exhausts the Redis
# memory limit. Applied as ``MAXLEN ~ n`` so XADD stays O(1).
MAXLEN = stream_maxlen(os.environ.get(STREAM_MAXLEN_ENV))


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
        self._prune_dead_consumers(streams_tuple, group, consumer_name)
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
                LOGGER.warning(
                    "service=%s stream=%s eventId=%s deliveries=%s subscriber poll/recovery failed: %s: %s",
                    group,
                    ",".join(streams_tuple),
                    "unknown",
                    0,
                    exc.__class__.__name__,
                    exc,
                    exc_info=True,
                )
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

    def _prune_dead_consumers(self, streams: Sequence[str], group: str, self_name: str) -> None:
        """Remove consumers idle > 5 min so their PEL entries are released instead of
        being auto-claimed in bulk to surviving consumers on scale-down."""
        for stream in streams:
            try:
                consumers = self._client.xinfo_consumers(stream, group)
                for consumer in consumers:
                    name = consumer.get("name") or consumer.get(b"name", b"")
                    if isinstance(name, bytes):
                        name = name.decode("utf-8")
                    if name == self_name:
                        continue
                    idle = int(consumer.get("idle", consumer.get(b"idle", 0)))
                    if idle > DEAD_CONSUMER_IDLE_MS:
                        pending = int(consumer.get("pending", consumer.get(b"pending", 0)))
                        self._client.execute_command("XGROUP", "DELCONSUMER", stream, group, name)
                        LOGGER.info(
                            "pruned dead consumer %s from %s/%s (idle=%dms, pending=%d)",
                            name, stream, group, idle, pending,
                        )
            except Exception:
                pass  # best-effort cleanup; stream or group may not exist yet

    def _recover_pending(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        for stream in streams:
            claimed = self._client.xautoclaim(stream, group, consumer_name, CLAIM_MIN_IDLE_MS, "0", count=CLAIM_COUNT)
            messages = claimed[1] if claimed and len(claimed) > 1 else []
            for msg_id, msg_data in messages:
                msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
                self._process_entry(
                    stream,
                    group,
                    msg_id_str,
                    msg_data,
                    handler,
                    self._delivery_count(stream, group, msg_id_str),
                    consumer_name,
                )

    def _process_results(self, results: Any, group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        for stream_name, messages in results or []:
            stream = stream_name.decode("utf-8") if isinstance(stream_name, bytes) else str(stream_name)
            for msg_id, msg_data in messages:
                msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
                self._process_entry(
                    stream,
                    group,
                    msg_id_str,
                    msg_data,
                    handler,
                    self._delivery_count(stream, group, msg_id_str),
                    consumer_name,
                )

    def _process_message(self, stream: str, group: str, consumer_name: str, msg_id: bytes | str, msg_data: Mapping[Any, Any], handler: Callable[[EventEnvelope], Any]) -> None:
        msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
        envelope_json = self._extract_envelope_json(msg_data)
        try:
            envelope = self._deserialize_envelope(json.loads(envelope_json))
        except Exception:
            self._move_to_dlq(stream, group, consumer_name, envelope_json or "{}", "DeserializeError", self._delivery_count(stream, group, msg_id_str) or 1)
            self._xack(stream, group, msg_id_str)
            return
        if self._already_seen(envelope.eventId):
            LOGGER.warning(
                "service=%s stream=%s eventId=%s deliveries=%s duplicate event already processed; acking without handler",
                group,
                stream,
                envelope.eventId,
                self._delivery_count(stream, group, msg_id_str) or 1,
            )
            self._xack(stream, group, msg_id_str)
            return
        if self._delivery_count(stream, group, msg_id_str) >= MAX_DELIVERY_ATTEMPTS:
            self._move_to_dlq(stream, group, consumer_name, envelope_json, "MaxDeliveryAttempts", self._delivery_count(stream, group, msg_id_str))
            self._xack(stream, group, msg_id_str)
            return
        try:
            result = _call_handler_with_span(handler, envelope, stream, group)
        except TransientHandlerError as exc:
            LOGGER.warning(
                "service=%s stream=%s eventId=%s deliveries=%s handler transient failure; message stays pending for retry: %s: %s",
                group,
                stream,
                envelope.eventId,
                self._delivery_count(stream, group, msg_id_str) or 1,
                exc.__class__.__name__,
                exc,
                exc_info=True,
            )
            return
        except FatalHandlerError as exc:
            self._move_to_dlq(stream, group, consumer_name, envelope_json, exc, self._delivery_count(stream, group, msg_id_str))
            self._xack(stream, group, msg_id_str)
            return
        except Exception as exc:
            deliveries = self._delivery_count(stream, group, msg_id_str) or 1
            if deliveries >= MAX_DELIVERY_ATTEMPTS:
                self._move_to_dlq(stream, group, consumer_name, envelope_json, exc, deliveries)
                self._xack(stream, group, msg_id_str)
            else:
                LOGGER.warning(
                    "service=%s stream=%s eventId=%s deliveries=%s handler unexpected failure; message stays pending for retry: %s: %s",
                    group,
                    stream,
                    envelope.eventId,
                    deliveries,
                    exc.__class__.__name__,
                    exc,
                    exc_info=True,
                )
            return
        if isinstance(result, HandlerResult):
            if result.status is HandlerStatus.TRANSIENT_ERROR:
                LOGGER.warning(
                    "service=%s stream=%s eventId=%s deliveries=%s handler transient result; message stays pending for retry: %s",
                    group,
                    stream,
                    envelope.eventId,
                    self._delivery_count(stream, group, msg_id_str) or 1,
                    result.message or "HandlerResult.TRANSIENT_ERROR",
                )
                return
            if result.status is HandlerStatus.FATAL_ERROR:
                self._move_to_dlq(stream, group, consumer_name, envelope_json, result.message or "HandlerResult.FATAL_ERROR", self._delivery_count(stream, group, msg_id_str))
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

    def _move_to_dlq(self, stream: str, group: str, consumer_name: str, envelope_json: str, reason: object, attempts: int) -> None:
        failure_reason = _truncate_failure_reason(reason)
        event_id = _event_id_for_log(envelope_json)
        safe_attempts = max(1, attempts)
        dead_lettered_at = datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
        LOGGER.warning(
            "service=%s stream=%s eventId=%s deliveries=%s consumerGroup=%s failureReason=%s attempts=%s deadLetteredAt=%s moving message to DLQ",
            group,
            stream,
            event_id,
            safe_attempts,
            group,
            failure_reason,
            safe_attempts,
            dead_lettered_at,
        )
        self._client.xadd(
            dlq_for_stream(stream),
            {
                "envelope": envelope_json,
                "consumerGroup": group,
                "consumerName": consumer_name,
                "failureReason": failure_reason,
                "attempts": str(safe_attempts),
                "deadLetteredAt": dead_lettered_at,
            },
            maxlen=MAXLEN,
            approximate=True,
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
        delivery_count: int,
        consumer_name: str | None = None,
    ) -> None:
        actual_consumer_name = consumer_name or default_consumer_name(group)
        if delivery_count >= MAX_DELIVERY_ATTEMPTS:
            envelope_json = self._extract_envelope_json(fields)
            self._move_to_dlq(stream, group, actual_consumer_name, envelope_json or "{}", "MaxDeliveryAttempts", delivery_count)
            self._xack(stream, group, entry_id)
            return
        self._process_message(stream, group, actual_consumer_name, entry_id, fields, handler)


def _call_handler_with_span(handler: Callable[[EventEnvelope], Any], envelope: EventEnvelope, stream: str, group: str) -> Any:
    if not otel_tracing_enabled():
        return handler(envelope)
    from opentelemetry import context, trace
    from opentelemetry.trace import SpanKind, Status, StatusCode

    attributes = {
        "messaging.system": "redis",
        "messaging.operation": "process",
        "messaging.destination.name": stream,
        "messaging.consumer.group.name": group,
        "messaging.train_ticket.stream": stream,
        "messaging.train_ticket.consumerGroup": group,
        "messaging.train_ticket.eventId": envelope.eventId,
        "messaging.train_ticket.eventType": envelope.eventType,
        "messaging.train_ticket.correlationId": envelope.correlationId,
    }
    span_name = f"{group} process {envelope.eventType}"
    parent_context = _remote_parent_context(envelope)
    with trace.get_tracer(__name__).start_as_current_span(span_name, context=parent_context or context.get_current(), kind=SpanKind.CONSUMER, attributes=attributes) as span:
        try:
            result = handler(envelope)
        except Exception as exc:
            span.record_exception(exc)
            span.set_status(Status(StatusCode.ERROR, exc.__class__.__name__))
            raise
        if isinstance(result, HandlerResult) and result.status is not HandlerStatus.SUCCESS:
            span.set_status(Status(StatusCode.ERROR, result.message or result.status.value))
        return result


def _remote_parent_context(envelope: EventEnvelope) -> Any | None:
    traceparent = getattr(envelope, "traceparent", None)
    if not isinstance(traceparent, str):
        return None
    parts = traceparent.split("-")
    if len(parts) != 4:
        return None
    version, trace_id, span_id, flags = parts
    if version != "00" or len(trace_id) != 32 or len(span_id) != 16 or len(flags) != 2:
        return None
    # W3C trace context is lowercase hex; reject uppercase for parity with
    # the ts-kit parser so malformed values are ignored identically everywhere.
    if trace_id != trace_id.lower() or span_id != span_id.lower() or flags != flags.lower():
        return None
    try:
        trace_id_int = int(trace_id, 16)
        span_id_int = int(span_id, 16)
        trace_flags = int(flags, 16)
    except ValueError:
        return None
    if trace_id_int == 0 or span_id_int == 0:
        return None
    try:
        from opentelemetry import context
        from opentelemetry.trace import SpanContext, TraceFlags, TraceState, set_span_in_context
        from opentelemetry.trace.span import NonRecordingSpan

        tracestate_value = getattr(envelope, "tracestate", None)
        trace_state = TraceState.from_header([tracestate_value]) if isinstance(tracestate_value, str) and tracestate_value else TraceState()
        span_context = SpanContext(
            trace_id=trace_id_int,
            span_id=span_id_int,
            is_remote=True,
            trace_flags=TraceFlags(trace_flags),
            trace_state=trace_state,
        )
        if not span_context.is_valid:
            return None
        return set_span_in_context(NonRecordingSpan(span_context), context.get_current())
    except Exception:
        return None


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
            result = _call_handler_with_span(handler, envelope, "in-memory", "in-memory")
            if not isinstance(result, HandlerResult) or result.status is HandlerStatus.SUCCESS:
                self.seen_event_ids.add(envelope.eventId)

    def stop(self) -> None:
        return None


def _truncate_failure_reason(reason: object) -> str:
    if isinstance(reason, BaseException):
        text = f"{reason.__class__.__name__}: {reason}"
    else:
        text = str(reason or "unknown")
    return text[:500]


def _event_id_for_log(envelope_json: str) -> str:
    try:
        raw = json.loads(envelope_json)
    except Exception:
        return "unknown"
    if isinstance(raw, dict):
        event_id = raw.get("eventId")
        if event_id is not None:
            return str(event_id)
    return "unknown"
