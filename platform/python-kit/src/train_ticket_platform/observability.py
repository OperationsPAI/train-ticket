from __future__ import annotations

import os
from typing import Any

_INITIALIZED = False


def otel_tracing_enabled() -> bool:
    exporter = os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower()
    return bool(exporter and exporter != "none")


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
    """
    global _INITIALIZED
    if not otel_tracing_enabled() and span_exporter is None:
        return None

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


def instrument_fastapi_app(app: Any) -> None:
    if not otel_tracing_enabled():
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
