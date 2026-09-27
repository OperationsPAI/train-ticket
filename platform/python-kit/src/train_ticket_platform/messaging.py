from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from enum import Enum
from datetime import UTC, datetime
import json
from collections import OrderedDict, deque
import inspect
import logging
import os
import socket
import threading
import time
from typing import Any, Protocol

from .events import EventEnvelope
from .metrics import (
    EventConsumerFailureType,
    EventConsumerMetrics,
    EventConsumerOutcome,
    event_consumer_metrics,
)
from .observability import otel_tracing_enabled


STREAM_MAXLEN_ENV = "EVENT_STREAM_MAXLEN"
DEFAULT_STREAM_MAXLEN = 10000
MAX_RETRIES = 3
RETRY_BACKOFF_SECONDS = (0.1, 0.3, 0.9)
MAX_DELIVERY_ATTEMPTS = 5
DEAD_CONSUMER_IDLE_MS = 5 * 60 * 1000
# How often the poll loop sweeps for dead consumers. Well below
# DEAD_CONSUMER_IDLE_MS so a corpse is reclaimed promptly once it qualifies, and
# far above the poll cadence so the sweep's XINFO CONSUMERS per stream stays
# negligible.
PRUNE_INTERVAL_MS = 60 * 1000
# Fifteen seconds, not one. Pending recovery is a whole XAUTOCLAIM round trip
# per stream per consumer, and an entry only becomes claimable after
# CLAIM_MIN_IDLE_MS of silence, so a faster sweep cannot find more. Measured on
# the resident cluster, XAUTOCLAIM was 4896 of Redis' ~19300 commands a second
# while it reclaimed nothing.
RECOVERY_INTERVAL_MS = 15000
# One second rather than a hundred milliseconds.
#
# A blocking read is woken by the write itself, so the block only bounds how
# long an idle reader sleeps before asking again -- it is not a delivery delay.
# Redis is single-threaded and was pinned at one core with 2015 connected
# clients; `xreadgroup` accounted for 275445579 of its calls and almost all of
# them were a hundred millisecond block expiring rather than a delivery.
POLL_BLOCK_MS = int(os.environ.get("CONSUMER_BLOCK_MS", "1000"))
# Five hundred rather than a hundred: one read that returns five hundred entries
# costs one command, and xreadgroup is the largest command class a
# single-threaded Redis serves (6928 of ~13300 a second on the resident release,
# with the server pinned at a core through every burst while every consumer
# group's own lag reads zero).
POLL_COUNT = int(os.environ.get("CONSUMER_BATCH_COUNT", "500"))
# Acknowledge in batches. See RedisEventSubscriber._xack: one XACK per delivered
# event per group was the largest command class on the shared Redis.
ACK_BATCHING = os.environ.get("CONSUMER_ACK_BATCHING", "1") != "0"
ACK_QUEUE_LIMIT = int(os.environ.get("CONSUMER_ACK_QUEUE_LIMIT", "500"))
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
    """Name a consumer uniquely per process, not per pod.

    The uvicorn services run several worker processes, each of which starts its
    own subscriber thread. Naming them after HOSTNAME alone gives every worker in
    a pod the same Redis consumer, so XREADGROUP hands the same entries to all of
    them and every copy but the first is discarded by the dedup check after
    paying the full cost of the handler.

    The pid makes a restarted process a new consumer, whose predecessor's PEL is
    released by the poll loop's periodic dead-consumer sweep.
    """
    instance = os.getenv("HOSTNAME") or socket.gethostname() or "local"
    safe_instance = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in instance)
    return f"{group}-{safe_instance}-{os.getpid()}"


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
    DEDUP_CACHE_LIMIT = 4096
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
        self._dedup_order: deque[str] = deque()
        self._handler_failures: OrderedDict[tuple[str, str, str], int] = OrderedDict()
        self._failure_lock = threading.Lock()
        self._dead_lettered: OrderedDict[tuple[str, str, str], bool] = OrderedDict()
        self._dead_letter_lock = threading.Lock()
        self._metrics: EventConsumerMetrics | None = None
        self._seen_event_ids = self._dedup
        self._dedup_lock = threading.Lock()
        self._seen_event_ids_lock = self._dedup_lock
        self._stop_requested = threading.Event()
        self._threads: list[threading.Thread] = []

    def _transport_metrics(self) -> EventConsumerMetrics:
        """This subscriber's delivery instruments, resolved once on first use.

        Resolved lazily rather than in ``__init__`` because the meter provider is
        installed at process startup, and a subscriber built before it (or built
        without ``__init__`` at all, as the unit tests do) must still work. The
        result is cached, so one subscriber registers one instrument set however
        many messages it delivers.
        """
        metrics = getattr(self, "_metrics", None)
        if metrics is None:
            metrics = event_consumer_metrics()
            self._metrics = metrics
        return metrics

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
        recovering = True
        next_prune_at = time.monotonic() + (PRUNE_INTERVAL_MS / 1000)
        next_recovery_at = time.monotonic() + (RECOVERY_INTERVAL_MS / 1000)
        self._stop_requested.clear()
        while not self._stop_requested.is_set():
            try:
                now = time.monotonic()
                if now >= next_prune_at:
                    self._prune_dead_consumers(streams_tuple, group, consumer_name)
                    next_prune_at = now + (PRUNE_INTERVAL_MS / 1000)
                if now >= next_recovery_at:
                    self._recover_idle_pending(streams_tuple, group, consumer_name, handler)
                    next_recovery_at = now + (RECOVERY_INTERVAL_MS / 1000)
                if recovering:
                    recovering = self._recover_own_pending(streams_tuple, group, consumer_name, handler)
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

    def start_independent_streams(
        self,
        streams: Sequence[str],
        group: str,
        handler: Callable[[EventEnvelope], Any],
        *,
        consumer_name: str | None = None,
        workers: int | None = None,
        workers_env: str = "CONSUMER_STREAM_WORKERS",
    ) -> list[threading.Thread]:
        """One subscriber thread per stream per worker.

        `start_in_background` runs a single thread for every stream it is given,
        so a burst on four streams is handled one message at a time: measured on
        the resident cluster, disruption-recovery's group fell 349 events behind
        on `events:post-sales` and 539 on `events:journey-order` inside one burst
        minute and a recovery case that completes on `PostSalesApplied` finished
        24 seconds after it started, which is past the load generator's poll.

        Every thread joins the same consumer group, so Redis shares the entries
        between them and a handler that is safe for one consumer is safe here.
        The default worker count is four per stream, overrideable per service.
        """
        resolved_name = consumer_name or default_consumer_name(group)
        resolved_workers = workers if workers is not None else int(os.environ.get(workers_env, "4"))
        if resolved_workers < 1:
            raise SubscribeFailed(f"{workers_env} must be positive")
        threads: list[threading.Thread] = []
        for index, stream in enumerate(streams):
            for worker in range(resolved_workers):
                threads.append(self.start_in_background(
                    (stream,), group, handler, consumer_name=f"{resolved_name}-{index}-{worker}"
                ))
        return threads

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
        """Remove idle consumers only when no pending entries belong to them."""
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
                    pending = int(consumer.get("pending", consumer.get(b"pending", 0)))
                    if idle > DEAD_CONSUMER_IDLE_MS and pending == 0:
                        removed = self._client.eval(
                            "if #redis.call('XPENDING', KEYS[1], ARGV[1], '-', '+', 1, ARGV[2]) ~= 0 then return 0 end; redis.call('XGROUP','DELCONSUMER',KEYS[1],ARGV[1],ARGV[2]); return 1",
                            1, stream, group, name,
                        )
                        if not removed:
                            continue
                        LOGGER.info(
                            "pruned idle consumer %s from %s/%s (idle=%dms)", name, stream, group, idle,
                        )
            except Exception:
                LOGGER.exception("consumer pruning failed for %s/%s", stream, group)

    def _recover_idle_pending(self, streams: Sequence[str], group: str, consumer_name: str,
                              handler: Callable[[EventEnvelope], Any], min_idle_ms: int = 60_000) -> None:
        if not hasattr(self, "_claim_cursors"):
            self._claim_cursors: dict[tuple[str, str, str], str] = {}
        for stream in streams:
            key = (stream, group, consumer_name)
            reply = self._client.xautoclaim(stream, group, consumer_name, min_idle_ms,
                self._claim_cursors.get(key, "0-0"), count=POLL_COUNT)
            self._claim_cursors[key] = reply[0]
            self._process_results([(stream, reply[1])], group, consumer_name, handler)

    def _recover_own_pending(self, streams: Sequence[str], group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> bool:
        """Re-deliver entries sitting in THIS consumer's PEL from a prior incarnation.

        Returns True while there are still entries to process, False when done.
        Processes at most one stream per call so the main '>' read can interleave.
        """
        for stream in streams:
            results = self._client.xreadgroup(
                group, consumer_name, {stream: "0"}, count=POLL_COUNT,
            )
            for stream_name, messages in results or []:
                if messages:
                    s = stream_name.decode("utf-8") if isinstance(stream_name, bytes) else str(stream_name)
                    for msg_id, msg_data in messages:
                        msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
                        self._process_entry(s, group, msg_id_str, msg_data, handler, 0, consumer_name)
                    self._flush_acks()
                    return True
        return False

    def _process_results(self, results: Any, group: str, consumer_name: str, handler: Callable[[EventEnvelope], Any]) -> None:
        for stream_name, messages in results or []:
            stream = stream_name.decode("utf-8") if isinstance(stream_name, bytes) else str(stream_name)
            self._ack_batch_depth = getattr(self, "_ack_batch_depth", 0) + 1
            try:
                for msg_id, msg_data in messages:
                    msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
                    self._process_entry(
                        stream,
                        group,
                        msg_id_str,
                        msg_data,
                        handler,
                        0,
                        consumer_name,
                    )
            finally:
                self._ack_batch_depth -= 1
            # One XACK per read batch per stream, not one per entry.
            self._flush_acks()

    def _process_message(self, stream: str, group: str, consumer_name: str, msg_id: bytes | str, msg_data: Mapping[Any, Any], handler: Callable[[EventEnvelope], Any]) -> None:
        msg_id_str = msg_id.decode("utf-8") if isinstance(msg_id, bytes) else str(msg_id)
        metrics = self._transport_metrics()
        envelope_json = self._extract_envelope_json(msg_data)
        if envelope_json is None:
            # An entry with no envelope field at all is a different integration
            # fault from one whose envelope will not parse, so the two are
            # classified apart. Both are unprocessable, so neither is retried.
            metrics.record_failure(EventConsumerFailureType.MISSING_ENVELOPE)
            self._move_to_dlq(
                stream,
                group,
                consumer_name,
                msg_id_str,
                "{}",
                "MissingEnvelope",
                self._delivery_count(stream, group, msg_id_str) or 1,
                EventConsumerFailureType.MISSING_ENVELOPE,
            )
            self._xack(stream, group, msg_id_str)
            return
        try:
            envelope = self._deserialize_envelope(json.loads(envelope_json))
        except Exception as exc:
            metrics.record_failure(EventConsumerFailureType.INVALID_ENVELOPE)
            self._move_to_dlq(
                stream,
                group,
                consumer_name,
                msg_id_str,
                envelope_json,
                exc,
                self._delivery_count(stream, group, msg_id_str) or 1,
                EventConsumerFailureType.INVALID_ENVELOPE,
            )
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
            metrics.record_outcome(EventConsumerOutcome.DUPLICATE)
            self._xack(stream, group, msg_id_str)
            return
        try:
            result = _call_handler_with_span(handler, envelope, stream, group)
        except TransientHandlerError as exc:
            metrics.record_failure(EventConsumerFailureType.TRANSIENT_RESULT)
            if self._failed_attempt(stream, group, consumer_name, msg_id_str, envelope_json, exc, EventConsumerFailureType.TRANSIENT_RESULT):
                return
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
            metrics.record_failure(EventConsumerFailureType.FATAL_RESULT)
            self._move_to_dlq(
                stream,
                group,
                consumer_name,
                msg_id_str,
                envelope_json,
                exc,
                self._delivery_count(stream, group, msg_id_str),
                EventConsumerFailureType.FATAL_RESULT,
            )
            self._xack(stream, group, msg_id_str)
            return
        except Exception as exc:
            metrics.record_failure(EventConsumerFailureType.HANDLER_EXCEPTION)
            if not self._failed_attempt(stream, group, consumer_name, msg_id_str, envelope_json, exc, EventConsumerFailureType.HANDLER_EXCEPTION):
                LOGGER.warning(
                    "service=%s stream=%s eventId=%s deliveries=%s handler unexpected failure; message stays pending for retry: %s: %s",
                    group,
                    stream,
                    envelope.eventId,
                    self._delivery_count(stream, group, msg_id_str) or 1,
                    exc.__class__.__name__,
                    exc,
                    exc_info=True,
                )
            return
        if isinstance(result, HandlerResult):
            if result.status is HandlerStatus.TRANSIENT_ERROR:
                metrics.record_failure(EventConsumerFailureType.TRANSIENT_RESULT)
                if self._failed_attempt(
                    stream,
                    group,
                    consumer_name,
                    msg_id_str,
                    envelope_json,
                    result.message or "HandlerResult.TRANSIENT_ERROR",
                    EventConsumerFailureType.TRANSIENT_RESULT,
                ):
                    return
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
                metrics.record_failure(EventConsumerFailureType.FATAL_RESULT)
                self._move_to_dlq(
                    stream,
                    group,
                    consumer_name,
                    msg_id_str,
                    envelope_json,
                    result.message or "HandlerResult.FATAL_ERROR",
                    self._delivery_count(stream, group, msg_id_str),
                    EventConsumerFailureType.FATAL_RESULT,
                )
                self._xack(stream, group, msg_id_str)
                return
        self._record_seen(envelope.eventId)
        failures = getattr(self, "_handler_failures", None)
        if failures is not None:
            with self._failure_lock:
                failures.pop((stream, group, msg_id_str), None)
        metrics.record_outcome(EventConsumerOutcome.SUCCESS)
        self._xack(stream, group, msg_id_str)

    def _failed_attempt(
        self,
        stream: str,
        group: str,
        consumer: str,
        entry_id: str,
        envelope_json: str,
        reason: object,
        failure_type: EventConsumerFailureType,
    ) -> bool:
        if not hasattr(self, "_handler_failures"):
            self._handler_failures = OrderedDict()
            self._failure_lock = threading.Lock()
        key = (stream, group, entry_id)
        with self._failure_lock:
            failures = self._handler_failures.get(key, 0) + 1
            self._handler_failures[key] = failures
            if failures >= MAX_DELIVERY_ATTEMPTS:
                self._handler_failures.pop(key)
            while len(self._handler_failures) > self.DEDUP_CACHE_LIMIT:
                self._handler_failures.popitem(last=False)
        if failures < MAX_DELIVERY_ATTEMPTS:
            # Below the limit the entry stays pending, which is a retry rather
            # than a terminal disposition. Counted here so a retry reads the
            # same way whichever failure asked for it.
            self._transport_metrics().record_outcome(EventConsumerOutcome.RETRY)
            return False
        self._move_to_dlq(stream, group, consumer, entry_id, envelope_json, reason, failures, failure_type)
        self._xack(stream, group, entry_id)
        return True

    def _extract_envelope_json(self, msg_data: Mapping[Any, Any]) -> str | None:
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
        # None, not "": an entry with no envelope field is classified apart from
        # one whose envelope is present but unparseable.
        return None

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
            self._remember_seen(seen, event_id)
            return
        with lock:
            self._remember_seen(seen, event_id)

    def _remember_seen(self, seen: set[str], event_id: str) -> None:
        if event_id in seen:
            return
        order = getattr(self, "_dedup_order", None)
        if order is None:
            order = deque(seen)
            self._dedup_order = order
        seen.add(event_id)
        order.append(event_id)
        while len(order) > self.DEDUP_CACHE_LIMIT:
            seen.discard(order.popleft())

    def _move_to_dlq(
        self,
        stream: str,
        group: str,
        consumer_name: str,
        entry_id: str,
        envelope_json: str,
        reason: object,
        attempts: int,
        failure_type: EventConsumerFailureType,
    ) -> None:
        """Hand one entry to the dead-letter stream, at most once per entry.

        The DLQ write and the ack that retires the entry are not atomic, so a
        crash between them re-delivers the entry. Without this guard that
        re-delivery writes a second DLQ record and counts a second dead letter
        for a single message; the marker is dropped again if the write raises, so
        a later delivery still retries the hand-off.
        """
        if not self._mark_dead_lettered(stream, group, entry_id):
            LOGGER.debug(
                "service=%s stream=%s entryId=%s already dead-lettered; skipping duplicate DLQ write",
                group,
                stream,
                entry_id,
            )
            return
        metrics = self._transport_metrics()
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
        try:
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
        except Exception:
            # The record did not land, so the hand-off is not done. Dropping the
            # marker lets a later delivery try again, and the exception reaches
            # the poll loop, which leaves the entry pending.
            self._forget_dead_lettered(stream, group, entry_id)
            metrics.record_failure(EventConsumerFailureType.DLQ_WRITE)
            raise
        metrics.record_dead_letter(failure_type)
        metrics.record_outcome(EventConsumerOutcome.DEAD_LETTER)

    def _mark_dead_lettered(self, stream: str, group: str, entry_id: str) -> bool:
        """Return True the first time this entry is dead-lettered, False after."""
        lock = getattr(self, "_dead_letter_lock", None)
        if lock is None:
            lock = self._dead_letter_lock = threading.Lock()
        marker = (stream, group, entry_id)
        with lock:
            dead_lettered = getattr(self, "_dead_lettered", None)
            if dead_lettered is None:
                dead_lettered = self._dead_lettered = OrderedDict()
            if marker in dead_lettered:
                return False
            dead_lettered[marker] = True
            while len(dead_lettered) > self.DEDUP_CACHE_LIMIT:
                dead_lettered.popitem(last=False)
            return True

    def _forget_dead_lettered(self, stream: str, group: str, entry_id: str) -> None:
        lock = getattr(self, "_dead_letter_lock", None)
        dead_lettered = getattr(self, "_dead_lettered", None)
        if lock is None or dead_lettered is None:
            return
        with lock:
            dead_lettered.pop((stream, group, entry_id), None)

    def _xack(self, stream: str, group: str, msg_id: str) -> None:
        """Acknowledge one entry, or queue it for the batch's single XACK.

        XACK takes any number of ids for the same stream and group, and this was
        one command per delivered event per consumer group -- measured at about
        7000 a second on the resident release, the largest single command class
        on a Redis that has one thread for every service. The queue is flushed at
        the end of each read batch and whenever it grows past a bound, so the
        worst case is that a crash re-delivers a batch that was handled but not
        yet acknowledged, which is the delivery guarantee this consumer already
        offers.
        """
        if not ACK_BATCHING or getattr(self, "_ack_batch_depth", 0) <= 0:
            # Outside a read batch there is no batch to join: a direct call -- a
            # dead-letter move, or a test driving a single entry -- acknowledges
            # now rather than queueing behind a flush that will not come.
            self._client.xack(stream, group, msg_id)
            return
        if not hasattr(self, "_ack_queue"):
            self._ack_queue: dict[tuple[str, str], list[str]] = {}
        self._ack_queue.setdefault((stream, group), []).append(msg_id)
        if sum(len(ids) for ids in self._ack_queue.values()) >= ACK_QUEUE_LIMIT:
            self._flush_acks()

    def _flush_acks(self) -> None:
        queue = getattr(self, "_ack_queue", None)
        if not queue:
            return
        self._ack_queue = {}
        for (stream, group), ids in queue.items():
            if ids:
                self._client.xack(stream, group, *ids)

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
        """Handle one delivered entry.

        `delivery_count` is accepted for callers that pass it and is not read:
        `_process_message` asks Redis for the count itself, and only on the paths
        that need it. It used to be resolved here for every message, which was an
        XPENDING round trip per delivery -- measured on the resident cluster,
        XPENDING was 2028 of Redis' ~14600 commands a second, and most of them
        were discarded. Redis is single-threaded, so that is the whole system's
        throughput being spent on an unused number.
        """
        actual_consumer_name = consumer_name or default_consumer_name(group)
        self._process_message(stream, group, actual_consumer_name, entry_id, fields, handler)


def handler_accepts_stream(handler: Callable[..., Any]) -> bool:
    """Whether this handler declared a second parameter for its stream.

    The subscriber already knows which stream an entry arrived on, and the
    audit row a handler writes wants it: `processed_events.stream` is the only
    record of which stream an event was consumed from, and the outbox writes
    the same value from the publishing side. A handler that only declares the
    envelope keeps the old single-argument contract, so this is opt-in and no
    existing service has to change to stay correct.

    Resolved once per handler and memoised, because it is consulted for every
    delivered message and `inspect.signature` is not cheap enough to call
    there. The handler itself is retained as the memo key's companion so an id
    cannot be reused by a different handler after the first is collected.
    """
    key = id(handler)
    cached = _HANDLER_STREAM_ARITY.get(key)
    if cached is not None and cached[0] is handler:
        return cached[1]
    arity = _handler_declares_stream(handler)
    _HANDLER_STREAM_ARITY[key] = (handler, arity)
    return arity


_HANDLER_STREAM_ARITY: dict[int, tuple[Any, bool]] = {}


def _handler_declares_stream(handler: Callable[..., Any]) -> bool:
    try:
        signature = inspect.signature(handler)
    except (TypeError, ValueError):
        return False
    positional = 0
    for parameter in signature.parameters.values():
        if parameter.kind is inspect.Parameter.VAR_POSITIONAL:
            return True
        if parameter.kind in (inspect.Parameter.POSITIONAL_ONLY, inspect.Parameter.POSITIONAL_OR_KEYWORD):
            positional += 1
    return positional >= 2


def _invoke_handler(handler: Callable[..., Any], envelope: EventEnvelope, stream: str) -> Any:
    if handler_accepts_stream(handler):
        return handler(envelope, stream)
    return handler(envelope)


def _call_handler_with_span(handler: Callable[[EventEnvelope], Any], envelope: EventEnvelope, stream: str, group: str) -> Any:
    if not otel_tracing_enabled():
        return _invoke_handler(handler, envelope, stream)
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
            result = _invoke_handler(handler, envelope, stream)
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
            # The in-memory transport has no stream key, so it keeps its own
            # name here rather than pretending to be a Redis stream.
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
