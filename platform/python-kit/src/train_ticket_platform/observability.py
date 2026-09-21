from __future__ import annotations

import os
from typing import Any

from .metrics import metric_export_interval_millis, metrics_enabled
from .trace_logging import install_trace_logging

_INITIALIZED = False
_METRICS_INITIALIZED = False


def otel_tracing_enabled() -> bool:
    exporter = os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower()
    return bool(exporter and exporter != "none")


def otel_metrics_enabled() -> bool:
    return metrics_enabled()


def init_opentelemetry(
    service_name: str | None = None,
    *,
    app: Any | None = None,
    span_exporter: Any | None = None,
) -> Any | None:
    """Initialize OpenTelemetry SDK and optional FastAPI instrumentation.

    The function is intentionally a no-op unless tracing is enabled through the
    standard OTEL_TRACES_EXPORTER environment variable or a test exporter is
    supplied. This keeps local/unit-test runtime behavior unchanged when OTEL_*
    is absent.

    Metrics are initialized from here for the same reason the log-record filter
    is: every service already calls this once at startup, so a service needs no
    edit of its own. The metrics half is gated separately on
    OTEL_METRICS_EXPORTER and runs even with tracing off, so either signal can be
    enabled alone.
    """
    global _INITIALIZED
    init_opentelemetry_metrics(service_name)
    if not otel_tracing_enabled() and span_exporter is None:
        # Still instrument the app when only metrics are on: the ASGI middleware
        # produces the HTTP server metrics as well as the spans.
        if app is not None:
            instrument_fastapi_app(app)
        return None

    # Installed here because every service already calls this once at startup,
    # so trace_id/span_id reach every log record without a per-service edit --
    # the same move java-kit makes by supplying a log pattern rather than
    # rewriting log statements. Inside the enabled branch: with tracing off
    # there are no ids and records are unchanged.
    install_trace_logging()

    from opentelemetry import trace
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor, SimpleSpanProcessor

    configured_service_name = service_name or os.getenv("OTEL_SERVICE_NAME", "train-ticket-service")
    provider = trace.get_tracer_provider()
    provider_was_installed = False
    if not _is_sdk_provider(provider):
        provider = TracerProvider(resource=Resource.create({"service.name": configured_service_name}))
        trace.set_tracer_provider(provider)
        provider_was_installed = True
        _INITIALIZED = True

    if span_exporter is not None:
        provider.add_span_processor(SimpleSpanProcessor(span_exporter))
    elif provider_was_installed or not getattr(provider, "_train_ticket_otlp_exporter_configured", False):
        if os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower() == "otlp":
            from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter

            provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
        setattr(provider, "_train_ticket_otlp_exporter_configured", True)
        _INITIALIZED = True

    if app is not None:
        instrument_fastapi_app(app)
    return provider


def init_opentelemetry_metrics(
    service_name: str | None = None,
    *,
    metric_reader: Any | None = None,
) -> Any | None:
    """Install an SDK meter provider and this process's runtime instrumentation.

    A no-op unless OTEL_METRICS_EXPORTER selects an exporter or a reader is
    supplied for a test, so a service run without a collector attempts no export.

    ``metric_reader`` replaces the periodic reader built from the OTLP exporter. A
    test passes an in-memory reader so collection happens when it asks rather
    than on a timer.
    """
    global _METRICS_INITIALIZED
    if not otel_metrics_enabled() and metric_reader is None:
        return None

    from opentelemetry import metrics
    from opentelemetry.sdk.metrics import MeterProvider
    from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
    from opentelemetry.sdk.resources import Resource

    configured_service_name = service_name or os.getenv("OTEL_SERVICE_NAME", "train-ticket-service")
    provider = metrics.get_meter_provider()
    if _is_sdk_meter_provider(provider):
        return provider

    if metric_reader is None:
        from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter

        metric_reader = PeriodicExportingMetricReader(
            OTLPMetricExporter(),
            export_interval_millis=metric_export_interval_millis(),
        )
    # Same resource shape as the tracer provider above: a span and a metric point
    # from one process must carry identical resource attributes, or a query that
    # joins them on service.name finds one signal and not the other.
    provider = MeterProvider(
        resource=Resource.create({"service.name": configured_service_name}),
        metric_readers=[metric_reader],
    )
    metrics.set_meter_provider(provider)
    _METRICS_INITIALIZED = True
    install_runtime_metrics(provider)
    return provider


def install_runtime_metrics(meter_provider: Any) -> None:
    """Publish this interpreter's own memory, thread and GC metrics.

    The instrumentor is used rather than hand-written gauges because its names
    are the ones the Python ecosystem publishes: process.memory.usage,
    process.thread.count, cpython.gc.collections and the rest.

    Only the process and interpreter groups are configured. The system.* groups
    the instrumentor also offers describe the node, which the kubeletstats and
    hostmetrics receivers already report on, and collecting them per pod would be
    the same numbers repeated once per service.
    """
    from opentelemetry.instrumentation.system_metrics import SystemMetricsInstrumentor

    instrumentor = SystemMetricsInstrumentor(
        config={
            "process.cpu.time": ["user", "system"],
            "process.cpu.utilization": None,
            "process.memory.usage": None,
            "process.memory.virtual": None,
            "process.thread.count": None,
            "process.open_file_descriptor.count": None,
            "cpython.gc.collections": None,
            "cpython.gc.collected_objects": None,
            "cpython.gc.uncollectable_objects": None,
        }
    )
    if instrumentor.is_instrumented_by_opentelemetry:
        return
    instrumentor.instrument(meter_provider=meter_provider)


def meter(service_name: str | None = None) -> Any | None:
    if not otel_metrics_enabled():
        return None
    from opentelemetry import metrics

    return metrics.get_meter(service_name or os.getenv("OTEL_SERVICE_NAME", "train-ticket-service"))


def instrument_fastapi_app(app: Any) -> None:
    """Instrument the ASGI app for traces, metrics, or both.

    FastAPIInstrumentor produces the HTTP server metrics
    (http.server.request.duration, http.server.active_requests) from the same
    middleware that produces the spans, so enabling metrics here needs no second
    middleware. It is therefore installed when either signal is on rather than
    only when tracing is.
    """
    if not otel_tracing_enabled() and not otel_metrics_enabled():
        return
    if getattr(app.state, "train_ticket_otel_instrumented", False):
        return
    from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

    FastAPIInstrumentor.instrument_app(app)
    app.state.train_ticket_otel_instrumented = True


def tracer(service_name: str | None = None) -> Any | None:
    if not otel_tracing_enabled():
        return None
    from opentelemetry import trace

    return trace.get_tracer(service_name or os.getenv("OTEL_SERVICE_NAME", "train-ticket-service"))


def _is_sdk_provider(provider: Any) -> bool:
    return hasattr(provider, "add_span_processor") and provider.__class__.__name__ != "ProxyTracerProvider"


def _is_sdk_meter_provider(provider: Any) -> bool:
    return provider.__class__.__name__ == "MeterProvider"
