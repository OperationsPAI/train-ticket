from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Response
from opentelemetry import trace

from train_ticket_platform.observability import init_opentelemetry
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
from .identity import HttpEligibilityCertificateAdapter
from .adapters.storage import PostgresFarePricingStore
from .adapters.messaging.publisher import RedisEventPublisher
from train_ticket_platform.messaging import default_consumer_name
from .adapters.messaging.subscriber import RedisEventSubscriber
from .ports.messaging import EventPublisher
from .adapters.messaging.handlers import CAPACITY_STREAM, IDENTITY_VERIFICATION_STREAM, handle_capacity_snapshot_updated, handle_identity_verification_event
from .web.errors import register_exception_handlers
from .web.handlers import router as fare_pricing_router
from .web.middleware import FareUnitOfWorkMiddleware, RequestContextMiddleware, RuntimeTracer, TraceHook


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
    return trace.get_tracer(configured_service)


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
    app.add_middleware(RequestContextMiddleware, tracer=tracer, otel_tracer=otel_tracer)

    # The probes are coroutines so they never queue behind the quoting operations.
    # FastAPI serves a `def` endpoint from anyio's shared 40-thread pool, and the quoting operations are `def` paths that hold a thread for the length of their blocking work.
    # health() and profile() are constant returns, and the readiness gate is an in-memory boolean, so on the event loop these cost nothing and a saturated pool cannot starve them.
    @app.get("/health")
    async def health_endpoint() -> dict[str, object]:
        return {"status": health(), "service": profile()}

    @app.get("/healthz")
    async def healthz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/live")
    async def live_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/ready")
    async def ready_endpoint(response: Response) -> dict[str, str]:
        gate = getattr(app.state, "readiness", None)
        if gate is not None and not gate.ready:
            response.status_code = 503
            return {"status": "not-ready"}
        return {"status": health()}

    @app.get("/readyz")
    async def readyz_endpoint(response: Response) -> dict[str, str]:
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
    service = FarePricingService(store, HttpEligibilityCertificateAdapter() if os.getenv("IDENTITY_VERIFICATION_URL") else None)
    unit_of_work = getattr(store, "unit_of_work", None)
    if callable(unit_of_work):
        app.add_middleware(FareUnitOfWorkMiddleware, unit_of_work=unit_of_work)

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
    if isinstance(store, PostgresFarePricingStore) and os.getenv("FARE_PRICING_DISABLE_SUBSCRIBER", "").lower() not in {"1", "true", "yes"}:
        subscriber = RedisEventSubscriber()
        subscriber.start_in_background(
            (CAPACITY_STREAM, IDENTITY_VERIFICATION_STREAM),
            "fare-pricing",
            lambda envelope: _handle_subscribed_event(service, envelope),
            consumer_name=default_consumer_name("fare-pricing"),
        )
        app.state.event_subscriber = subscriber
    app.include_router(fare_pricing_router)


def _handle_subscribed_event(service: FarePricingService, envelope: Any) -> None:
    if envelope.producer == "identity-verification":
        handle_identity_verification_event(service, envelope)
        return
    handle_capacity_snapshot_updated(service, envelope)


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
                    per_km_rate=Decimal("0.15"),
                    minimum_fare=Money(Decimal("5.00"), "CNY"),
                    distance_discount_threshold_km=Decimal("500"),
                    distance_discount_pct=10,
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
    migrations_dir = Path(env_dir) if env_dir else Path(__file__).resolve().parents[2] / "migrations"
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
    init_opentelemetry(profile()["service_id"], app=app)
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
        subscriber = getattr(app.state, "event_subscriber", None) or getattr(app.state, "capacity_subscriber", None)
        if subscriber is not None:
            subscriber.stop()
        pool = getattr(app.state, "database_pool", None)
        if pool is not None:
            pool.close()

    return app
