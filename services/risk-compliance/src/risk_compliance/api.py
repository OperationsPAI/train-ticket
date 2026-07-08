from __future__ import annotations

from collections.abc import AsyncIterator, Callable, Mapping
from contextlib import AbstractContextManager, asynccontextmanager, nullcontext
import os
from pathlib import Path
from typing import Any, Protocol

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from train_ticket_platform.observability import init_opentelemetry
from .application import (
    AssessmentNotFoundError,
    BlockNotFoundError,
    InMemoryAssessmentRepository,
    PublishFailed,
    RiskComplianceService,
    is_uuid7,
    uuid7,
)
from train_ticket_platform.http import canonical_error_body
from train_ticket_platform.idempotency import BoundedInMemoryIdempotencyStore, IdempotencyStore, configure_idempotency_middleware
from train_ticket_platform.storage import DatabaseConfig, DatabasePool, OutboxRelay, PostgresIdempotencyStore, ReadinessGate, run_migrations

from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-ID"
CORRELATION_ID_HEADER = "X-Correlation-ID"
IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
TraceHook = Callable[[str, Mapping[str, object]], None]


class RuntimeSpan(AbstractContextManager["RuntimeSpan"], Protocol):
    def set_attribute(self, key: str, value: object) -> None: ...

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool | None: ...


class RuntimeTracer(Protocol):
    def start_as_current_span(self, name: str) -> RuntimeSpan: ...


class AssessRiskRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    subjectRef: str = Field(min_length=1)
    scenario: str = Field(pattern="^(order_risk|payment_risk|post_sales_risk|account_risk)$")
    context: dict[str, Any]


class LiftRiskBlockRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    subjectRef: str = Field(min_length=1)
    scope: str = Field(pattern="^(ORDER|PAYMENT|ACCOUNT)$")
    reasonCode: str = Field(min_length=1)


class ErrorBody(BaseModel):
    code: str
    message: str
    correlationId: str
    details: dict[str, Any]


def _emit_trace(tracer: TraceHook | None, event: str, attributes: Mapping[str, object]) -> None:
    if tracer is not None:
        tracer(event, dict(attributes))


def opentelemetry_tracer_from_env(service_name: str) -> RuntimeTracer | None:
    """Return an OpenTelemetry API tracer only when OTEL_TRACES_EXPORTER is enabled.

    The OpenTelemetry API defaults to non-recording spans unless a service
    bootstrap installs an SDK/exporter. That keeps tests collector-free while
    allowing OTEL_* environment configuration to drive real deployments.
    """
    import os

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
    request_id = str(uuid7())
    supplied_correlation_id = None
    for name, value in request.headers.items():
        if name.lower() == "x-correlation-id":
            supplied_correlation_id = value
            break
    correlation_id = supplied_correlation_id if supplied_correlation_id and is_uuid7(supplied_correlation_id) else str(uuid7())
    return request_id, correlation_id


def error_response(request: Request, status_code: int, code: str, message: str, details: dict[str, Any] | None = None) -> JSONResponse:
    correlation_id = getattr(request.state, "correlation_id", str(uuid7()))
    return JSONResponse(
        status_code=status_code,
        content=ErrorBody(
            code=code,
            message=message,
            correlationId=correlation_id,
            details=details or {},
        ).model_dump(),
    )


def _risk_error_body(request: Request, code: str, message: str) -> dict[str, Any]:
    return canonical_error_body(code, message, getattr(request.state, "correlation_id", str(uuid7())))


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

    @app.get("/live")
    def live_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/ready")
    def ready_endpoint(response: Response) -> dict[str, str]:
        if not _storage_ready(app):
            response.status_code = 503
            return {"status": "not-ready"}
        return {"status": health()}

    @app.get("/healthz")
    def healthz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/readyz")
    def readyz_endpoint(response: Response) -> dict[str, str]:
        if not _storage_ready(app):
            response.status_code = 503
            return {"status": "not-ready"}
        return {"status": health()}

    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]:
        return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}



def _storage_ready(app: FastAPI) -> bool:
    gate = getattr(app.state, "readiness", None)
    if gate is not None and not gate.ready:
        return False
    pool = getattr(app.state, "database_pool", None)
    if pool is None:
        return True
    try:
        with pool.connection() as conn:
            conn.execute("SELECT 1").fetchone()
        return True
    except Exception:
        if gate is not None:
            gate.mark_failed("database readiness check failed")
        return False


def _configure_postgres(app: FastAPI) -> tuple[InMemoryAssessmentRepository, IdempotencyStore | None, Any | None]:
    config = DatabaseConfig.from_env()
    if config is None:
        return InMemoryAssessmentRepository(), None, None
    from .adapters.storage import PostgresAssessmentRepository, TransactionalOutboxPublisher

    readiness = ReadinessGate()
    pool = DatabasePool(config)
    app.state.database_pool = pool
    app.state.readiness = readiness
    env_dir = os.environ.get("MIGRATIONS_DIR")
    migrations_dir = Path(env_dir) if env_dir else Path(__file__).resolve().parents[2] / "migrations"
    run_migrations(pool, migrations_dir, readiness)
    repository = PostgresAssessmentRepository(pool)
    publisher = TransactionalOutboxPublisher(repository)
    relay = OutboxRelay(pool)
    relay.start()
    app.state.outbox_relay = relay
    return repository, PostgresIdempotencyStore(pool), publisher

def configure_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(
            request,
            400,
            "VALIDATION_FAILED",
            "Request validation failed",
            {"errors": exc.errors()},
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code == 404:
            return error_response(request, 404, "NOT_FOUND", "The requested resource was not found")
        return error_response(request, exc.status_code, "UNAVAILABLE", str(exc.detail))


def configure_risk_endpoints(app: FastAPI, service: RiskComplianceService) -> None:
    @app.post("/api/v1/risk-assessments", status_code=201, response_model=None)
    def assess_risk_endpoint(
        request: Request,
        payload: AssessRiskRequest,
    ) -> JSONResponse | dict[str, Any]:
        try:
            result, _ = service.assess(
                subject_ref=payload.subjectRef,
                scenario=payload.scenario,
                context=payload.context,
                idempotency_key=request.headers[IDEMPOTENCY_KEY_HEADER],
                correlation_id=request.state.correlation_id,
                idempotency_scope=request.state.idempotency_decision.scope,
                idempotency_fingerprint=request.state.idempotency_decision.fingerprint,
            )
        except PublishFailed:
            return error_response(
                request,
                503,
                "UNAVAILABLE",
                "Risk assessment was saved but its result event could not be published; retry with the same Idempotency-Key to retrieve the saved result.",
                {"deliverySemantics": "AT_LEAST_ONCE"},
            )
        return JSONResponse(status_code=201, content=result.to_dict())

    @app.get("/api/v1/risk-assessments/{assessmentId}", response_model=None)
    def get_risk_assessment(request: Request, assessmentId: str) -> dict[str, Any] | JSONResponse:
        try:
            return service.get_assessment(assessmentId).to_dict()
        except AssessmentNotFoundError:
            return error_response(request, 404, "NOT_FOUND", "Risk assessment was not found")

    @app.post("/api/v1/risk-blocks/lift", status_code=201, response_model=None)
    def lift_risk_block(request: Request, payload: LiftRiskBlockRequest) -> JSONResponse:
        try:
            lifted = service.lift_block(
                subject_ref=payload.subjectRef,
                scope=payload.scope,
                reason_code=payload.reasonCode,
                correlation_id=request.state.correlation_id,
            )
        except BlockNotFoundError:
            return error_response(request, 404, "NOT_FOUND", "Active risk block was not found")
        except PublishFailed:
            return error_response(request, 503, "UNAVAILABLE", "Risk block lift event could not be published")
        return JSONResponse(status_code=201, content=lifted.to_dict())


def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    service: RiskComplianceService | None = None,
    idempotency_store: IdempotencyStore | None = None,
) -> FastAPI:
    store = idempotency_store
    subscriber_config: dict[str, Any] | None = None

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if subscriber_config is not None:
            app.state.subscriber.start_in_background(
                subscriber_config["subscriptions"],
                subscriber_config["consumer_group"],
                app.state.risk_service.handle_event,
                consumer_name=subscriber_config["consumer_name"],
            )
        try:
            yield
        finally:
            if subscriber_config is not None:
                app.state.subscriber.stop()
            relay = getattr(app.state, "outbox_relay", None)
            if relay is not None:
                relay.stop()
            pool = getattr(app.state, "database_pool", None)
            if pool is not None:
                pool.close()

    app = FastAPI(title="Risk & Compliance", version="0.1.0", lifespan=lifespan)
    init_opentelemetry(profile()["service_id"], app=app)
    repository, postgres_idempotency_store, postgres_publisher = _configure_postgres(app)
    store = store or postgres_idempotency_store or BoundedInMemoryIdempotencyStore()
    app.state.assessment_repository = repository
    if service is None:
        from .adapters.messaging.redis_streams import (
            RISK_COMPLIANCE_CONSUMER_GROUP,
            RISK_COMPLIANCE_SUBSCRIPTIONS,
            RedisEventPublisher,
            RedisEventSubscriber,
            risk_compliance_consumer_name,
        )

        app.state.publisher = postgres_publisher or RedisEventPublisher()
        app.state.risk_service = RiskComplianceService(
            publisher=app.state.publisher,
            repository=app.state.assessment_repository,
            idempotency_store=store,
        )
        app.state.subscriber = RedisEventSubscriber()
        subscriber_config = {
            "subscriptions": RISK_COMPLIANCE_SUBSCRIPTIONS,
            "consumer_group": RISK_COMPLIANCE_CONSUMER_GROUP,
            "consumer_name": risk_compliance_consumer_name(),
        }
    else:
        app.state.risk_service = service
        app.state.publisher = service.publisher
        app.state.risk_service.idempotency_store = store
    configure_error_handlers(app)
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    configure_idempotency_middleware(
        app,
        store,
        require_key=True,
        include_path_prefixes=("/api/v1/risk-assessments", "/api/v1/risk-blocks"),
        error_body_factory=_risk_error_body,
    )
    configure_risk_endpoints(app, app.state.risk_service)
    return app
