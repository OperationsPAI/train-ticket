from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, nullcontext
from typing import Any, Protocol

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

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
    def ready_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/healthz")
    def healthz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/readyz")
    def readyz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]:
        return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}


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
    app = FastAPI(title="Risk & Compliance", version="0.1.0")
    app.state.assessment_repository = InMemoryAssessmentRepository()
    store = idempotency_store or BoundedInMemoryIdempotencyStore()
    if service is None:
        from .adapters.messaging.redis_streams import RedisEventPublisher

        app.state.publisher = RedisEventPublisher()
        app.state.risk_service = RiskComplianceService(
            publisher=app.state.publisher,
            repository=app.state.assessment_repository,
            idempotency_store=store,
        )
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
    if service is None:
        from .adapters.messaging.redis_streams import (
            RISK_COMPLIANCE_CONSUMER_GROUP,
            RISK_COMPLIANCE_SUBSCRIPTIONS,
            RedisEventSubscriber,
            risk_compliance_consumer_name,
        )

        app.state.subscriber = RedisEventSubscriber()

        @app.on_event("startup")
        def start_risk_subscriber() -> None:
            app.state.subscriber.start_in_background(
                RISK_COMPLIANCE_SUBSCRIPTIONS,
                RISK_COMPLIANCE_CONSUMER_GROUP,
                app.state.risk_service.handle_event,
                consumer_name=risk_compliance_consumer_name(),
            )

        @app.on_event("shutdown")
        def stop_risk_subscriber() -> None:
            app.state.subscriber.stop()
    return app
