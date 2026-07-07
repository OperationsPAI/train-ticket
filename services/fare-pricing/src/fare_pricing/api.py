from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, nullcontext
import os
from pathlib import Path
from typing import Any, Protocol

from fastapi import FastAPI, Request, Response

from .ids import prefixed_uuid7, uuid7
from .runtime import health, profile
from .application import DomainEventService
from train_ticket_platform.idempotency import BoundedInMemoryIdempotencyStore, IdempotencyStore, configure_idempotency_middleware
from train_ticket_platform.storage import (
    DatabaseConfig,
    DatabasePool,
    OutboxRelay,
    PostgresIdempotencyStore,
    ReadinessGate,
    run_migrations,
)
from .application.service import FarePricingService, InMemoryStore
from .adapters.storage import PostgresFarePricingStore
from .adapters.messaging.publisher import RedisEventPublisher
from .ports.messaging import EventPublisher
from .web.errors import register_exception_handlers
from .web.handlers import router as fare_pricing_router

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
    """Return an OpenTelemetry API tracer only when OTEL_TRACES_EXPORTER is enabled.

    The OpenTelemetry API defaults to non-recording spans unless a service
    bootstrap installs an SDK/exporter. That keeps tests collector-free while
    allowing OTEL_* environment configuration to drive real deployments.
    """
    exporter = os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower()
    if not exporter or exporter == "none":
        return None
    configured_service = os.getenv("OTEL_SERVICE_NAME", "").strip() or service_name
    try:
        from opentelemetry import trace
    except ImportError:  # pragma: no cover - optional adapter dependency
        return None
    return trace.get_tracer(configured_service)


def _span_context(tracer: RuntimeTracer | None, name: str) -> AbstractContextManager[RuntimeSpan | None]:
    if tracer is None:
        return nullcontext(None)
    return tracer.start_as_current_span(name)


def _set_span_attribute(span: RuntimeSpan | None, key: str, value: object) -> None:
    if span is not None:
        span.set_attribute(key, value)


def _request_identifiers(request: Request) -> tuple[str, str]:
    request_id = request.headers.get(REQUEST_ID_HEADER) or uuid7()
    correlation_id = request.headers.get(CORRELATION_ID_HEADER) or request.headers.get(LEGACY_CORRELATION_ID_HEADER) or prefixed_uuid7("corr")
    return request_id, correlation_id


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next: Any):
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
        service_name = profile()["service_id"]
        path = request.url.path
        trace_attributes = {
            "service.name": service_name,
            "request_id": request_id,
            "correlation_id": correlation_id,
            "http.request.method": request.method,
            "http.method": request.method,
            "url.path": path,
            "http.route": path,
            "http.request_id": request_id,
            "http.correlation_id": correlation_id,
        }
        _emit_trace(tracer, "http.request.start", trace_attributes)
        with _span_context(otel_tracer, f"{request.method} {path}") as span:
            for key, value in trace_attributes.items():
                _set_span_attribute(span, key, value)
            try:
                response = await call_next(request)
            except Exception as exc:  # pragma: no cover - exercised by FastAPI exception handling paths
                _set_span_attribute(span, "error.type", exc.__class__.__name__)
                _emit_trace(tracer, "http.request.error", {**trace_attributes, "error": exc.__class__.__name__})
                raise
            response.headers[REQUEST_ID_HEADER] = request_id
            response.headers[CORRELATION_ID_HEADER] = correlation_id
            response.headers[LEGACY_CORRELATION_ID_HEADER] = correlation_id
            _set_span_attribute(span, "http.response.status_code", response.status_code)
            _set_span_attribute(span, "http.status_code", response.status_code)
            _emit_trace(
                tracer,
                "http.request.complete",
                {**trace_attributes, "status_code": response.status_code},
            )
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
        gate = getattr(app.state, "readiness", None)
        if gate is not None and not gate.ready:
            response.status_code = 503
            return {"status": "not-ready"}
        return {"status": health()}

    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]:
        return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}


def configure_fare_pricing_routes(
    app: FastAPI,
    store: InMemoryStore | None = None,
    idempotency_store: IdempotencyStore | None = None,
    event_publisher: EventPublisher | None = None,
) -> None:
    """Register the fare-pricing business API routes and application service."""
    if store is None:
        store = InMemoryStore()
    service = FarePricingService(store)
    unit_of_work = getattr(store, "unit_of_work", None)
    if callable(unit_of_work):
        @app.middleware("http")
        async def fare_pricing_unit_of_work_middleware(request: Request, call_next: Any):
            with unit_of_work():
                return await call_next(request)

    publisher = event_publisher or RedisEventPublisher()
    app.state.publish_events_synchronously = not isinstance(store, PostgresFarePricingStore)
    app.state.fare_pricing_service = service
    app.state.fare_pricing_store = store
    app.state.domain_event_service = DomainEventService(publisher)
    app.state.event_publisher = publisher
    configure_idempotency_middleware(
        app,
        idempotency_store or BoundedInMemoryIdempotencyStore(),
        require_key=True,
        include_path_prefixes=("/api/v1/fare-quotes", "/api/v1/adjustment-quotes", "/api/v1/fare-rule-sets"),
    )
    app.include_router(fare_pricing_router)


def _install_default_rule_sets(store: Any) -> None:
    """Fallback pricing for empty deployments until managed rule sets are published."""
    from datetime import UTC, datetime, timedelta
    from decimal import Decimal

    from .domain import (
        FareRule,
        FareRuleSet,
        Money,
        PriceExplanation,
        RuleKind,
        ValidityWindow,
    )

    try:
        if store.all_rule_sets():
            return
    except AttributeError:
        if store.fare_rule_sets:
            return
    now = datetime.now(UTC)
    window = ValidityWindow(now - timedelta(days=1), now + timedelta(days=365))
    for index, channel in enumerate(("WEB", "web", "MOBILE", "COUNTER")):
        rule_set = FareRuleSet(
            rule_set_id=f"ruleset-default-{index}-{channel}",
            supplier_id="supplier-default",
            product_code="rail-standard",
            mode="rail",
            channel=channel,
            version="phase1-default",
            effective_window=window,
            rules=(
                FareRule(
                    rule_id="base",
                    kind=RuleKind.BASE_FARE,
                    amount=Money(Decimal("100.00"), "CNY"),
                    explanation=PriceExplanation("fare.base", {"rule": "base"}),
                ),
                FareRule(
                    rule_id="tax",
                    kind=RuleKind.TAX,
                    amount=Money(Decimal("7.50"), "CNY"),
                    explanation=PriceExplanation("fare.tax", {"rule": "tax"}),
                ),
                FareRule(
                    rule_id="refund-fee",
                    kind=RuleKind.REFUND_FEE,
                    amount=Money(Decimal("20.00"), "CNY"),
                    explanation=PriceExplanation("fare.refund_fee", {"rule": "refund-fee"}),
                ),
                FareRule(
                    rule_id="change-fee",
                    kind=RuleKind.CHANGE_FEE,
                    amount=Money(Decimal("15.00"), "CNY"),
                    explanation=PriceExplanation("fare.change_fee", {"rule": "change-fee"}),
                ),
            ),
            contract_id="contract-default",
        ).publish(now)
        store.save_rule_set(rule_set)


def _postgres_store_from_env(app: FastAPI) -> tuple[Any, IdempotencyStore | None]:
    config = DatabaseConfig.from_env()
    if config is None:
        store = InMemoryStore()
        _install_default_rule_sets(store)
        return store, None  # type: ignore[return-value]
    readiness = ReadinessGate()
    pool = DatabasePool(config)
    app.state.database_pool = pool
    app.state.readiness = readiness
    # In the container the package lives in site-packages, so the source-tree
    # heuristic below cannot find the SQL; the image sets MIGRATIONS_DIR.
    env_dir = os.environ.get("MIGRATIONS_DIR")
    migrations_dir = Path(env_dir) if env_dir else Path(__file__).resolve().parents[3] / "migrations"
    run_migrations(pool, migrations_dir, readiness)
    store = PostgresFarePricingStore(pool)
    _install_default_rule_sets(store)
    relay = OutboxRelay(pool)
    relay.start()
    app.state.outbox_relay = relay
    return store, PostgresIdempotencyStore(pool)


def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    store: InMemoryStore | None = None,
    idempotency_store: IdempotencyStore | None = None,
    event_publisher: EventPublisher | None = None,
) -> FastAPI:
    app = FastAPI(title='Fare & Pricing', version="0.1.0")
    if store is None:
        store, default_idempotency_store = _postgres_store_from_env(app)
        idempotency_store = idempotency_store or default_idempotency_store
    register_exception_handlers(app)
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    configure_fare_pricing_routes(app, store, idempotency_store, event_publisher)

    @app.on_event("shutdown")
    def _shutdown_storage() -> None:
        relay = getattr(app.state, "outbox_relay", None)
        if relay is not None:
            relay.stop()
        pool = getattr(app.state, "database_pool", None)
        if pool is not None:
            pool.close()

    return app
