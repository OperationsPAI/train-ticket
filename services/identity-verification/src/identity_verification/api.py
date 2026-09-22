from __future__ import annotations

import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, Response
from opentelemetry import trace
from train_ticket_platform.idempotency import BoundedInMemoryIdempotencyStore, IdempotencyStore, configure_idempotency_middleware
from .adapters.messaging.subscriber import RedisEventSubscriber
from train_ticket_platform.observability import init_opentelemetry
from train_ticket_platform.storage import DatabaseConfig, DatabasePool, OutboxRelay, PostgresIdempotencyStore, ReadinessGate, run_migrations

from .adapters.messaging import TRAVELER_PROFILE_STREAM

JOURNEY_ORDER_STREAM = "events:journey-order"
RISK_COMPLIANCE_STREAM = "events:risk-compliance"
from .adapters.storage.postgres import PostgresIdentityVerificationStore
from .application.service import DeterministicSimGateway, IdentityVerificationService, InMemoryStore
from .runtime import health, profile
from .web.errors import register_exception_handlers
from .web.handlers import router as identity_router
from .web.middleware import IdentityUnitOfWorkMiddleware, RequestContextMiddleware, RuntimeTracer, TraceHook


def opentelemetry_tracer_from_env(service_name: str) -> RuntimeTracer | None:
    exporter = os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower()
    if not exporter or exporter == "none": return None
    return trace.get_tracer(os.getenv("OTEL_SERVICE_NAME", "").strip() or service_name)


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
    app.add_middleware(RequestContextMiddleware, tracer=tracer, otel_tracer=otel_tracer)

    # The probes are coroutines so they never queue behind the verification operations.
    # FastAPI serves a `def` endpoint from anyio's shared 40-thread pool, and the verification operations are `def` paths that hold a thread for the length of their blocking work.
    # health() and profile() are constant returns, and the readiness gate is an in-memory boolean, so on the event loop these cost nothing and a saturated pool cannot starve them.
    @app.get("/health")
    async def health_endpoint() -> dict[str, object]: return {"status": health(), "service": profile()}
    @app.get("/healthz")
    async def healthz_endpoint() -> dict[str, str]: return {"status": health()}
    @app.get("/live")
    async def live_endpoint() -> dict[str, str]: return {"status": health()}
    @app.get("/ready")
    async def ready_endpoint(response: Response) -> dict[str, str]:
        gate = getattr(app.state, "readiness", None)
        if gate is not None and not gate.ready:
            response.status_code = 503; return {"status": "not-ready"}
        return {"status": health()}
    @app.get("/readyz")
    async def readyz_endpoint(response: Response) -> dict[str, str]: return await ready_endpoint(response)
    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]: return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}


def configure_identity_routes(app: FastAPI, store: Any | None = None, idempotency_store: IdempotencyStore | None = None) -> None:
    store = store or InMemoryStore()
    service = IdentityVerificationService(store, DeterministicSimGateway(os.getenv("IDENTITY_SIM_SEED", "sim-tail-v1")))
    unit_of_work = getattr(store, "unit_of_work", None)
    if callable(unit_of_work):
        app.add_middleware(IdentityUnitOfWorkMiddleware, unit_of_work=unit_of_work)
    app.state.identity_verification_service = service; app.state.identity_verification_store = store
    configure_idempotency_middleware(app, idempotency_store or BoundedInMemoryIdempotencyStore(), require_key=True, include_path_prefixes=("/api/v1/identity-verification/credentials", "/api/v1/identity-verification/verification-cases", "/api/v1/identity-verification/eligibility-certificates", "/api/v1/identity-verification/pre-order-checks", "/api/v1/identity-verification/purchase-limit-facts", "/api/v1/identity-verification/verifications"))
    for route in identity_router.routes: app.router.routes.append(route)


def _postgres_store_from_env(app: FastAPI) -> tuple[Any, IdempotencyStore | None]:
    config = DatabaseConfig.from_env()
    if config is None: return InMemoryStore(), None
    readiness = ReadinessGate(); pool = DatabasePool(config)
    app.state.database_pool = pool; app.state.readiness = readiness
    migrations_dir = Path(os.environ.get("MIGRATIONS_DIR") or Path(__file__).resolve().parents[2] / "migrations")
    run_migrations(pool, migrations_dir, readiness)
    store = PostgresIdentityVerificationStore(pool); relay = OutboxRelay(pool); relay.start()
    subscriber = RedisEventSubscriber(); holder: dict[str, Any] = {}
    def handle(envelope: Any) -> None:
        holder["service"].handle_upstream_event(envelope)
    thread = subscriber.start_in_background((TRAVELER_PROFILE_STREAM, JOURNEY_ORDER_STREAM, RISK_COMPLIANCE_STREAM), "identity-verification", handle)
    app.state.outbox_relay = relay; app.state.event_subscriber = subscriber; app.state.event_subscriber_thread = thread; app.state._identity_service_holder = holder
    return store, PostgresIdempotencyStore(pool)


def create_app(tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None, store: Any | None = None, idempotency_store: IdempotencyStore | None = None) -> FastAPI:
    app = FastAPI(title="Identity Verification", version="0.1.0")
    init_opentelemetry(profile()["service_id"], app=app)
    if store is None:
        store, default_idempotency_store = _postgres_store_from_env(app); idempotency_store = idempotency_store or default_idempotency_store
    register_exception_handlers(app); configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"])); configure_identity_routes(app, store, idempotency_store)
    holder = getattr(app.state, "_identity_service_holder", None)
    if isinstance(holder, dict): holder["service"] = app.state.identity_verification_service
    @app.on_event("shutdown")
    def _shutdown() -> None:
        sub = getattr(app.state, "event_subscriber", None)
        if sub is not None: sub.stop()
        relay = getattr(app.state, "outbox_relay", None)
        if relay is not None: relay.stop()
        pool = getattr(app.state, "database_pool", None)
        if pool is not None: pool.close()
    return app
