from __future__ import annotations

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

__all__ = [
    "POOL_NAME",
    "metrics_enabled",
    "metric_export_interval_millis",
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
