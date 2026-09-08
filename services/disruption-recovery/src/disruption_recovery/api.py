from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, nullcontext
import os
from pathlib import Path
from typing import Any, Protocol

from fastapi import FastAPI, Request, Response

from train_ticket_platform.idempotency import BoundedInMemoryIdempotencyStore, IdempotencyStore, configure_idempotency_middleware
from train_ticket_platform.messaging import RedisEventSubscriber
from train_ticket_platform.observability import init_opentelemetry
from train_ticket_platform.storage import DatabaseConfig, DatabasePool, OutboxRelay, PostgresIdempotencyStore, ReadinessGate, run_migrations
from train_ticket_platform.ids import new_uuid7

from .adapters.messaging import INBOUND_STREAMS
from .adapters.storage.postgres import PostgresDisruptionRecoveryStore
from .application.service import DisruptionRecoveryService, InMemoryStore
from .downstream import DownstreamHttpClient
from .runtime import health, profile
from .web.errors import register_exception_handlers
from .web.handlers import router as disruption_router

REQUEST_ID_HEADER = "X-Request-ID"
CORRELATION_ID_HEADER = "X-Correlation-Id"
LEGACY_CORRELATION_ID_HEADER = "X-Correlation-ID"
TraceHook = Callable[[str, Mapping[str, object]], None]


class RuntimeSpan(AbstractContextManager["RuntimeSpan"], Protocol):
    def set_attribute(self, key: str, value: object) -> None: ...
    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool | None: ...


class RuntimeTracer(Protocol):
    def start_as_current_span(self, name: str) -> RuntimeSpan: ...


def _emit_trace(tracer: TraceHook | None, event: str, attributes: Mapping[str, object]) -> None:
    if tracer is not None:
        tracer(event, dict(attributes))


def opentelemetry_tracer_from_env(service_name: str) -> RuntimeTracer | None:
    exporter = os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower()
    if not exporter or exporter == "none":
        return None
    configured_service = os.getenv("OTEL_SERVICE_NAME", "").strip() or service_name
    try:
        from opentelemetry import trace
    except ImportError:
        return None
    return trace.get_tracer(configured_service)


def _span_context(tracer: RuntimeTracer | None, name: str) -> AbstractContextManager[RuntimeSpan | None]:
    return nullcontext(None) if tracer is None else tracer.start_as_current_span(name)


def _set_span_attribute(span: RuntimeSpan | None, key: str, value: object) -> None:
    if span is not None:
        span.set_attribute(key, value)


def _request_identifiers(request: Request) -> tuple[str, str]:
    request_id = request.headers.get(REQUEST_ID_HEADER) or new_uuid7()
    correlation_id = request.headers.get(CORRELATION_ID_HEADER) or request.headers.get(LEGACY_CORRELATION_ID_HEADER) or request_id
    return request_id, correlation_id


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next: Any):
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
        service_name = profile()["service_id"]
        path = request.url.path
        attrs = {"service.name": service_name, "request_id": request_id, "correlation_id": correlation_id, "http.request.method": request.method, "http.method": request.method, "url.path": path, "http.route": path, "http.request_id": request_id, "http.correlation_id": correlation_id}
        _emit_trace(tracer, "http.request.start", attrs)
        with _span_context(otel_tracer, f"{request.method} {path}") as span:
            for key, value in attrs.items():
                _set_span_attribute(span, key, value)
            try:
                response = await call_next(request)
            except Exception as exc:
                _set_span_attribute(span, "error.type", exc.__class__.__name__)
                _emit_trace(tracer, "http.request.error", {**attrs, "error": exc.__class__.__name__})
                raise
            response.headers[REQUEST_ID_HEADER] = request_id
            response.headers[CORRELATION_ID_HEADER] = correlation_id
            response.headers[LEGACY_CORRELATION_ID_HEADER] = correlation_id
            _set_span_attribute(span, "http.response.status_code", response.status_code)
            _set_span_attribute(span, "http.status_code", response.status_code)
            _emit_trace(tracer, "http.request.complete", {**attrs, "status_code": response.status_code})
            return response

    @app.get("/health")
    def health_endpoint() -> dict[str, object]:
        return {"status": health(), "service": profile()}

    @app.get("/healthz")
    def healthz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/live")
    def live_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/ready")
    def ready_endpoint(response: Response) -> dict[str, str]:
        gate = getattr(app.state, "readiness", None)
        if gate is not None and not gate.ready:
            response.status_code = 503
            return {"status": "not-ready"}
        return {"status": health()}

    @app.get("/readyz")
    def readyz_endpoint(response: Response) -> dict[str, str]:
        return ready_endpoint(response)

    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]:
        return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}


def configure_disruption_routes(app: FastAPI, store: Any | None = None, idempotency_store: IdempotencyStore | None = None, downstream: Any | None = None) -> None:
    store = store or InMemoryStore()
    service = DisruptionRecoveryService(store, downstream or (DownstreamHttpClient() if DatabaseConfig.from_env() else None))
    unit_of_work = getattr(store, "unit_of_work", None)
    if callable(unit_of_work):
        @app.middleware("http")
        async def disruption_unit_of_work_middleware(request: Request, call_next: Any):
            with unit_of_work():
                return await call_next(request)
    app.state.disruption_recovery_service = service
    app.state.disruption_recovery_store = store
    configure_idempotency_middleware(app, idempotency_store or BoundedInMemoryIdempotencyStore(), require_key=True, include_path_prefixes=("/api/v1/disruptions", "/api/v1/recovery-cases"))
    for route in disruption_router.routes:
        app.router.routes.append(route)


def _postgres_store_from_env(app: FastAPI) -> tuple[Any, IdempotencyStore | None]:
    config = DatabaseConfig.from_env()
    if config is None:
        return InMemoryStore(), None
    readiness = ReadinessGate()
    pool = DatabasePool(config)
    app.state.database_pool = pool
    app.state.readiness = readiness
    migrations_dir = Path(os.environ.get("MIGRATIONS_DIR") or Path(__file__).resolve().parents[2] / "migrations")
    run_migrations(pool, migrations_dir, readiness)
    store = PostgresDisruptionRecoveryStore(pool)
    relay = OutboxRelay(pool)
    relay.start()
    subscriber = RedisEventSubscriber()
    service_holder: dict[str, Any] = {}
    def handle(envelope: Any) -> Any:
        return service_holder["service"].handle_inbound_event(envelope)
    thread = subscriber.start_in_background(INBOUND_STREAMS, "disruption-recovery", handle)
    app.state.outbox_relay = relay
    app.state.event_subscriber = subscriber
    app.state.event_subscriber_thread = thread
    app.state._dr_service_holder = service_holder
    return store, PostgresIdempotencyStore(pool)


def create_app(tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None, store: Any | None = None, idempotency_store: IdempotencyStore | None = None, downstream: Any | None = None) -> FastAPI:
    app = FastAPI(title="Disruption Recovery", version="0.1.0")
    init_opentelemetry(profile()["service_id"], app=app)
    if store is None:
        store, default_idempotency_store = _postgres_store_from_env(app)
        idempotency_store = idempotency_store or default_idempotency_store
    register_exception_handlers(app)
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    configure_disruption_routes(app, store, idempotency_store, downstream)
    holder = getattr(app.state, "_dr_service_holder", None)
    if isinstance(holder, dict):
        holder["service"] = app.state.disruption_recovery_service

    @app.on_event("shutdown")
    def _shutdown() -> None:
        sub = getattr(app.state, "event_subscriber", None)
        if sub is not None:
            sub.stop()
        relay = getattr(app.state, "outbox_relay", None)
        if relay is not None:
            relay.stop()
        pool = getattr(app.state, "database_pool", None)
        if pool is not None:
            pool.close()
    return app
