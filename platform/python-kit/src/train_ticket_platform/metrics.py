from __future__ import annotations

from enum import Enum
import logging
import os
from typing import Any, Iterable

from opentelemetry.metrics import CallbackOptions, Meter, Observation
from opentelemetry.semconv._incubating.attributes.db_attributes import (
    DB_CLIENT_CONNECTION_POOL_NAME,
    DB_CLIENT_CONNECTION_STATE,
    DbClientConnectionStateValues,
)
from opentelemetry.semconv._incubating.metrics.db_metrics import (
    DB_CLIENT_CONNECTION_COUNT,
    DB_CLIENT_CONNECTION_MAX,
    DB_CLIENT_CONNECTION_PENDING_REQUESTS,
    DB_CLIENT_CONNECTION_TIMEOUTS,
    DB_CLIENT_CONNECTION_WAIT_TIME,
)

# The pool this kit creates, named so the attribute is comparable across
# services. Every Python service runs exactly one.
POOL_NAME = "platform-python-kit-postgres"

_DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS = 60_000

LOGGER = logging.getLogger(__name__)

__all__ = [
    "EVENT_CONSUMER_FAILURE_TYPES",
    "EVENT_CONSUMER_OUTCOMES",
    "OUTBOX_FAILURE_STAGES",
    "POOL_NAME",
    "EventConsumerFailureType",
    "EventConsumerMetrics",
    "EventConsumerOutcome",
    "OutboxFailureStage",
    "OutboxMetrics",
    "event_consumer_metrics",
    "metrics_enabled",
    "metric_export_interval_millis",
    "order_funnel",
    "OrderFunnel",
    "outbox_metrics",
    "register_pool_metrics",
]


def metrics_enabled() -> bool:
    exporter = os.getenv("OTEL_METRICS_EXPORTER", "").strip().lower()
    return bool(exporter and exporter != "none")


def metric_export_interval_millis() -> int:
    """Read the standard SDK variable, in milliseconds.

    An unparseable or non-positive value falls back to the specification's own
    default of 60 seconds. This governs only how often metrics are sent, and a
    service must still start.
    """
    configured = os.getenv("OTEL_METRIC_EXPORT_INTERVAL", "").strip()
    if not configured:
        return _DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS
    try:
        millis = int(configured)
    except ValueError:
        return _DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS
    return millis if millis > 0 else _DEFAULT_METRIC_EXPORT_INTERVAL_MILLIS


def register_pool_metrics(meter: Meter, pool: Any, pool_name: str = POOL_NAME) -> None:
    """Publish a psycopg pool's state under the database client conventions.

    The instruments are observable rather than synchronous: occupancy is a level
    to be sampled, and psycopg_pool already maintains every number in
    ``get_stats()``. A callback reading that dictionary is both cheaper than
    mirroring each checkout and closer to the truth, since the pool counts its
    own waiters.

    ``pool`` is anything exposing psycopg_pool's ``get_stats()``, which is what
    :class:`train_ticket_platform.storage.DatabasePool` wraps.
    """
    pool_attributes = {DB_CLIENT_CONNECTION_POOL_NAME: pool_name}
    used_attributes = {
        **pool_attributes,
        DB_CLIENT_CONNECTION_STATE: DbClientConnectionStateValues.USED.value,
    }
    idle_attributes = {
        **pool_attributes,
        DB_CLIENT_CONNECTION_STATE: DbClientConnectionStateValues.IDLE.value,
    }

    def stats() -> dict[str, int]:
        return pool.get_stats()

    def observe_connection_count(options: CallbackOptions) -> Iterable[Observation]:
        current = stats()
        available = current.get("pool_available", 0)
        # psycopg reports the pool's total size and how many of those are
        # available, not how many are checked out, so "used" is the difference.
        # A negative result is impossible but would corrupt a sum, so it is
        # clamped.
        used = max(current.get("pool_size", 0) - available, 0)
        yield Observation(used, used_attributes)
        yield Observation(available, idle_attributes)

    def observe_connection_max(options: CallbackOptions) -> Iterable[Observation]:
        yield Observation(stats().get("pool_max", 0), pool_attributes)

    def observe_pending_requests(options: CallbackOptions) -> Iterable[Observation]:
        yield Observation(stats().get("requests_waiting", 0), pool_attributes)

    def observe_timeouts(options: CallbackOptions) -> Iterable[Observation]:
        # requests_errors counts acquisitions that failed, which for this pool is
        # dominated by the acquire timeout; psycopg keeps no separate timeout
        # counter.
        yield Observation(stats().get("requests_errors", 0), pool_attributes)

    def observe_wait_time(options: CallbackOptions) -> Iterable[Observation]:
        # requests_wait_ms is a cumulative total, so what can be derived from it
        # honestly is a sum rather than a distribution. It is published as a
        # counter of seconds for that reason: dividing it by the request count
        # gives the mean wait, and claiming percentiles this data cannot support
        # would be worse than not having them.
        yield Observation(stats().get("requests_wait_ms", 0) / 1000.0, pool_attributes)

    meter.create_observable_up_down_counter(
        name=DB_CLIENT_CONNECTION_COUNT,
        callbacks=[observe_connection_count],
        description="The number of connections that are currently in state described by the state attribute.",
        unit="{connection}",
    )
    meter.create_observable_up_down_counter(
        name=DB_CLIENT_CONNECTION_MAX,
        callbacks=[observe_connection_max],
        description="The maximum number of open connections allowed.",
        unit="{connection}",
    )
    meter.create_observable_up_down_counter(
        name=DB_CLIENT_CONNECTION_PENDING_REQUESTS,
        callbacks=[observe_pending_requests],
        description="The number of current pending requests for an open connection.",
        unit="{request}",
    )
    meter.create_observable_counter(
        name=DB_CLIENT_CONNECTION_TIMEOUTS,
        callbacks=[observe_timeouts],
        description="The number of connection timeouts that have occurred trying to obtain a connection from the pool.",
        unit="{timeout}",
    )
    meter.create_observable_counter(
        name=DB_CLIENT_CONNECTION_WAIT_TIME,
        callbacks=[observe_wait_time],
        description="The time it took to obtain an open connection from the pool.",
        unit="s",
    )


# The order funnel's closed vocabularies, shared with the Java services that
# publish the same counter. Every label value is a member of one of these sets or
# the fallback `other`, so a series can never be named after a request, an order
# or an exception message.
ORDER_FUNNEL_STAGES = ("search", "quote", "order", "reservation", "payment", "ticket")
ORDER_FUNNEL_OUTCOMES = ("attempted", "accepted", "rejected")
ORDER_FUNNEL_CHANNELS = ("web", "mobile", "counter", "partner", "api")
OTHER = "other"


def _vocabulary(value: str | None, allowed: tuple[str, ...]) -> str:
    if value is None:
        return OTHER
    normalized = value.strip().lower()
    return normalized if normalized in allowed else OTHER


class OrderFunnel:
    """Publishes `train_ticket.order.transitions` for the stages one service owns.

    A reader takes `rate()` over it and gets the funnel of a stage by outcome and
    purchase channel. The counter is a plain synchronous instrument: the
    transition has already happened when it is recorded, so there is nothing for
    an observable to read.

    One stage records `attempted` once and then exactly one of `accepted` or
    `rejected`, which is what keeps `attempted = accepted + rejected` for that
    stage. Services that own no stage get the no-op instance `NOOP`.
    """

    def __init__(self, meter: Meter | None) -> None:
        self._counter = (
            None
            if meter is None
            else meter.create_counter(
                name="train_ticket.order.transitions",
                description="Order funnel transitions by stage, outcome and purchase channel.",
                unit="{transition}",
            )
        )

    def transition(self, stage: str, outcome: str, channel: str | None) -> None:
        if self._counter is None:
            return
        self._counter.add(
            1,
            {
                "stage": _vocabulary(stage, ORDER_FUNNEL_STAGES),
                "outcome": _vocabulary(outcome, ORDER_FUNNEL_OUTCOMES),
                "channel": _vocabulary(channel, ORDER_FUNNEL_CHANNELS),
            },
        )


def order_funnel(meter: Meter | None = None, service_name: str | None = None) -> OrderFunnel:
    """The funnel instrument for this process, or a no-op when metrics are off.

    `service_name` is accepted so a caller can pass the name it already uses for
    its other instruments; the meter's own scope name is what reaches the
    backend, so both paths produce the same series.
    """
    if meter is None:
        from .observability import meter as process_meter

        meter = process_meter(service_name)
    return OrderFunnel(meter)


# The process-level transport vocabularies, held to the same contract as the
# Java platform kit's EventConsumerMetrics/OutboxMetrics enums so one recording
# rule and one dashboard read every runtime. They are closed for the same reason
# the funnel's are: a stream key, a consumer group, an event type or an exception
# message as an attribute would create a series per integration, and these
# values are enough to tell healthy processing from retry pressure, poison
# messages and a stalled relay.
EVENT_CONSUMER_OUTCOMES = ("success", "duplicate", "retry", "dead_letter")
EVENT_CONSUMER_FAILURE_TYPES = (
    "handler_exception",
    "transient_result",
    "fatal_result",
    "missing_envelope",
    "invalid_envelope",
    "redelivery_exhausted",
    "dlq_write",
)
OUTBOX_FAILURE_STAGES = ("select", "publish", "mark_published")

# One warning per distinct out-of-vocabulary value. A caller that passes one is
# broken, but it must not turn into a series named after the value, and a hot
# recording path must not become a log flood either.
_UNKNOWN_TRANSPORT_LABELS: set[str] = set()


class EventConsumerOutcome(str, Enum):
    """How one delivery of an event ended."""

    SUCCESS = "success"
    DUPLICATE = "duplicate"
    RETRY = "retry"
    DEAD_LETTER = "dead_letter"


class EventConsumerFailureType(str, Enum):
    """Why a delivery failed, or why a message was dead-lettered."""

    HANDLER_EXCEPTION = "handler_exception"
    TRANSIENT_RESULT = "transient_result"
    FATAL_RESULT = "fatal_result"
    MISSING_ENVELOPE = "missing_envelope"
    INVALID_ENVELOPE = "invalid_envelope"
    REDELIVERY_EXHAUSTED = "redelivery_exhausted"
    DLQ_WRITE = "dlq_write"


class OutboxFailureStage(str, Enum):
    """Which stage of one relay pass failed."""

    SELECT = "select"
    PUBLISH = "publish"
    MARK_PUBLISHED = "mark_published"


def _transport_label(value: object, allowed: tuple[str, ...], attribute: str) -> str | None:
    """Normalize one closed-vocabulary label, or refuse to invent a series.

    The Java instruments take enums, so an out-of-vocabulary value cannot reach
    them at all. Here a caller may pass either the enum member or the bare value,
    and anything else is dropped with one warning per distinct value rather than
    becoming a series named after an exception message.
    """
    text = value.value if isinstance(value, Enum) else str(value)
    text = text.strip().lower()
    if text in allowed:
        return text
    if text not in _UNKNOWN_TRANSPORT_LABELS:
        _UNKNOWN_TRANSPORT_LABELS.add(text)
        LOGGER.warning("%s=%r is outside %s; the sample is not recorded", attribute, value, allowed)
    return None


class EventConsumerMetrics:
    """One Redis event delivery's disposition, under the shared contract.

    Three counters rather than one with three labels: an outcome always exists
    while a failure classification only exists for failures, and keeping them
    apart is what lets `messages` be read as a delivery rate.

    A service with OTEL metrics off gets the no-op instance, so a recording call
    is never a decision point and never an exception on the message path.
    """

    def __init__(self, meter: Meter | None) -> None:
        self._messages: Any | None = None
        self._failures: Any | None = None
        self._dead_letters: Any | None = None
        if meter is None:
            return
        self._messages = meter.create_counter(
            name="train_ticket.event.consumer.messages",
            description="Event deliveries by their processing disposition.",
            unit="{message}",
        )
        self._failures = meter.create_counter(
            name="train_ticket.event.consumer.failures",
            description="Event-consumer failures by a bounded failure classification.",
            unit="{failure}",
        )
        self._dead_letters = meter.create_counter(
            name="train_ticket.event.consumer.dead_letters",
            description="Messages successfully handed to a dead-letter stream.",
            unit="{message}",
        )

    def record_outcome(self, outcome: EventConsumerOutcome | str) -> None:
        if self._messages is None:
            return
        label = _transport_label(outcome, EVENT_CONSUMER_OUTCOMES, "outcome")
        if label is None:
            return
        self._messages.add(1, {"outcome": label})

    def record_failure(self, failure_type: EventConsumerFailureType | str) -> None:
        if self._failures is None:
            return
        label = _transport_label(failure_type, EVENT_CONSUMER_FAILURE_TYPES, "failure.type")
        if label is None:
            return
        self._failures.add(1, {"failure.type": label})

    def record_dead_letter(self, reason: EventConsumerFailureType | str) -> None:
        if self._dead_letters is None:
            return
        label = _transport_label(reason, EVENT_CONSUMER_FAILURE_TYPES, "reason")
        if label is None:
            return
        self._dead_letters.add(1, {"reason": label})


class OutboxMetrics:
    """Health signals of one process's transactional outbox relay.

    The backlog is an observable gauge over the bounded head sample the relay
    query already returns, never a count of the table: an exact `count(*)` during
    a large backlog is a new source of database pressure at the moment the
    database is already the bottleneck. A sample equal to the relay's batch size
    means at least one full batch is waiting.

    The two gauge values are plain attributes written by the relay thread and
    read by the metric reader's callback thread; each write is a single
    reference assignment, so a reader sees the last completed poll rather than a
    torn one.
    """

    def __init__(self, meter: Meter | None) -> None:
        self._unpublished_sample = 0
        self._oldest_unpublished_seconds = 0.0
        self._published: Any | None = None
        self._failures: Any | None = None
        if meter is None:
            return
        meter.create_observable_gauge(
            name="train_ticket.outbox.unpublished.sample",
            callbacks=[self._observe_unpublished_sample],
            description="Rows visible in the relay's bounded unpublished head sample.",
            unit="{event}",
        )
        meter.create_observable_gauge(
            name="train_ticket.outbox.oldest_unpublished.age",
            callbacks=[self._observe_oldest_unpublished_age],
            description="Age of the head-of-line unpublished outbox event.",
            unit="s",
        )
        self._published = meter.create_counter(
            name="train_ticket.outbox.published",
            description="Outbox events whose bounded Redis publish pipeline completed successfully.",
            unit="{event}",
        )
        self._failures = meter.create_counter(
            name="train_ticket.outbox.failures",
            description="Outbox relay failures by a bounded processing stage.",
            unit="{failure}",
        )

    def _observe_unpublished_sample(self, options: CallbackOptions) -> Iterable[Observation]:
        del options
        yield Observation(int(self._unpublished_sample))

    def _observe_oldest_unpublished_age(self, options: CallbackOptions) -> Iterable[Observation]:
        del options
        yield Observation(float(self._oldest_unpublished_seconds))

    def observe_unpublished_sample(self, rows: int, oldest_age_seconds: float) -> None:
        # Clamped rather than trusted: a negative age is a clock skew, and a
        # negative gauge value would corrupt any rate or max taken over it.
        self._unpublished_sample = max(int(rows), 0)
        self._oldest_unpublished_seconds = max(float(oldest_age_seconds), 0.0)

    def record_published(self, rows: int) -> None:
        if self._published is None or rows <= 0:
            return
        self._published.add(rows)

    def record_failure(self, stage: OutboxFailureStage | str) -> None:
        if self._failures is None:
            return
        label = _transport_label(stage, OUTBOX_FAILURE_STAGES, "stage")
        if label is None:
            return
        self._failures.add(1, {"stage": label})


def event_consumer_metrics(
    meter: Meter | None = None, service_name: str | None = None
) -> EventConsumerMetrics:
    """The event-consumer instruments for this process, or a no-op when off."""
    if meter is None:
        from .observability import meter as process_meter

        meter = process_meter(service_name)
    return EventConsumerMetrics(meter)


def outbox_metrics(meter: Meter | None = None, service_name: str | None = None) -> OutboxMetrics:
    """The outbox-relay instruments for this process, or a no-op when off."""
    if meter is None:
        from .observability import meter as process_meter

        meter = process_meter(service_name)
    return OutboxMetrics(meter)
