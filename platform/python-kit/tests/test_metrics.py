"""Application metrics leaving a Python service.

These tests pin the two things that make the metrics readable: they must arrive
at all, and they must arrive under the names the Python ecosystem publishes. A
reader querying for a saturated pool looks for db.client.connection.*, not for a
name invented here.
"""

from __future__ import annotations

import pytest
from opentelemetry import metrics
from opentelemetry.sdk.metrics.export import InMemoryMetricReader

from train_ticket_platform.metrics import POOL_NAME, metric_export_interval_millis, register_pool_metrics
from train_ticket_platform.observability import init_opentelemetry_metrics


class FakePool:
    """A pool reporting psycopg_pool's own get_stats() shape.

    The numbers matter, not the connections: register_pool_metrics reads nothing
    else, so no Postgres is needed to prove the wiring.
    """

    def __init__(self, **stats: int) -> None:
        self._stats = stats

    def get_stats(self) -> dict[str, int]:
        return dict(self._stats)


@pytest.fixture(scope="module")
def metric_reader() -> InMemoryMetricReader:
    """One provider for the whole module.

    The meter provider is process-global and can be set once per interpreter, so
    a provider per test would leave every later reader unregistered and
    collecting nothing.
    """
    reader = InMemoryMetricReader()
    provider = init_opentelemetry_metrics("python-kit-test", metric_reader=reader)
    assert provider is not None
    return reader


def collected_metrics(reader: InMemoryMetricReader) -> dict[str, object]:
    data = reader.get_metrics_data()
    by_name: dict[str, object] = {}
    for resource_metric in data.resource_metrics:
        for scope_metric in resource_metric.scope_metrics:
            for metric in scope_metric.metrics:
                by_name[metric.name] = metric
    return by_name


def test_metrics_are_not_initialized_without_an_exporter(monkeypatch):
    monkeypatch.setenv("OTEL_METRICS_EXPORTER", "none")
    assert init_opentelemetry_metrics("python-kit-test") is None


def test_metric_export_interval_falls_back_to_the_specification_default(monkeypatch):
    monkeypatch.delenv("OTEL_METRIC_EXPORT_INTERVAL", raising=False)
    assert metric_export_interval_millis() == 60_000

    monkeypatch.setenv("OTEL_METRIC_EXPORT_INTERVAL", "15000")
    assert metric_export_interval_millis() == 15_000

    # A value that is not a positive number of milliseconds governs only the
    # export period, so it falls back rather than failing startup.
    monkeypatch.setenv("OTEL_METRIC_EXPORT_INTERVAL", "not-a-number")
    assert metric_export_interval_millis() == 60_000
    monkeypatch.setenv("OTEL_METRIC_EXPORT_INTERVAL", "0")
    assert metric_export_interval_millis() == 60_000


def test_pool_metrics_publish_convention_names(metric_reader):
    # pool_size is the total, pool_available how many of those are free, so three
    # of ten are checked out.
    pool = FakePool(
        pool_min=1,
        pool_max=10,
        pool_size=10,
        pool_available=7,
        requests_waiting=4,
        requests_errors=2,
        requests_wait_ms=1_500,
    )
    register_pool_metrics(metrics.get_meter("python-kit-test"), pool)

    collected = collected_metrics(metric_reader)
    for name in (
        "db.client.connection.count",
        "db.client.connection.max",
        "db.client.connection.pending_requests",
        "db.client.connection.timeouts",
        "db.client.connection.wait_time",
    ):
        assert name in collected, f"missing {name}; collected {sorted(collected)}"

    points = {
        point.attributes.get("db.client.connection.state"): point.value
        for point in collected["db.client.connection.count"].data.data_points
    }
    assert points == {"used": 3, "idle": 7}

    # The signal the missing case needed: callers queued on an exhausted pool.
    pending = collected["db.client.connection.pending_requests"].data.data_points
    assert [point.value for point in pending] == [4]
    assert pending[0].attributes["db.client.connection.pool.name"] == POOL_NAME

    max_points = collected["db.client.connection.max"].data.data_points
    assert [point.value for point in max_points] == [10]

    # Milliseconds in psycopg, seconds in the convention.
    wait = collected["db.client.connection.wait_time"].data.data_points
    assert [point.value for point in wait] == [1.5]


def test_runtime_metrics_publish_python_ecosystem_names(metric_reader):
    collected = collected_metrics(metric_reader)
    for name in ("process.memory.usage", "process.thread.count", "cpython.gc.collections"):
        assert name in collected, f"missing {name}; collected {sorted(collected)}"
