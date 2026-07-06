from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, nullcontext
from typing import Any, Protocol

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from train_ticket_platform.idempotency import BoundedInMemoryIdempotencyStore, IdempotencyStore, configure_idempotency_middleware
from train_ticket_platform.ids import is_uuid7

from .application import LegacyAclService, LegacyContext
from .downstream import DownstreamClient

from .ids import prefixed_uuid7
from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-Id"
CORRELATION_ID_HEADER = "X-Correlation-Id"
LEGACY_OPERATOR_HEADER = "X-Legacy-Operator"
LEGACY_REASON_HEADER = "X-Legacy-Reason"
IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
TraceHook = Callable[[str, Mapping[str, object]], None]


class RuntimeSpan(AbstractContextManager["RuntimeSpan"], Protocol):
    def set_attribute(self, key: str, value: object) -> None: ...

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool | None: ...


class RuntimeTracer(Protocol):
    def start_as_current_span(self, name: str) -> RuntimeSpan: ...


def opentelemetry_tracer_from_env(service_name: str) -> RuntimeTracer | None:
    import os

    exporter = os.getenv("OTEL_TRACES_EXPORTER", "").strip().lower()
    if not exporter or exporter == "none":
        return None
    configured_service = os.getenv("OTEL_SERVICE_NAME", "").strip() or service_name
    try:
        from opentelemetry import trace
    except ImportError:  # pragma: no cover
        return None
    return trace.get_tracer(configured_service)


def _span_context(tracer: RuntimeTracer | None, name: str) -> AbstractContextManager[RuntimeSpan | None]:
    if tracer is None:
        return nullcontext(None)
    return tracer.start_as_current_span(name)


def _set_span_attribute(span: RuntimeSpan | None, key: str, value: object) -> None:
    if span is not None:
        span.set_attribute(key, value)


def _emit_trace(tracer: TraceHook | None, event: str, attributes: Mapping[str, object]) -> None:
    if tracer is not None:
        tracer(event, dict(attributes))


def _canonical_correlation_id(value: str | None) -> str:
    if not value or not value.strip():
        return prefixed_uuid7("corr")
    supplied = value.strip()
    if supplied.startswith("corr-") and is_uuid7(supplied[5:]):
        return supplied
    if is_uuid7(supplied):
        return f"corr-{supplied}"
    return prefixed_uuid7("corr")


def _request_identifiers(request: Request) -> tuple[str, str]:
    request_id = request.headers.get(REQUEST_ID_HEADER) or prefixed_uuid7("req")
    correlation_id = _canonical_correlation_id(request.headers.get(CORRELATION_ID_HEADER))
    return request_id, correlation_id


def legacy_body(status: int, msg: str, data: Mapping[str, Any] | None = None) -> dict[str, Any]:
    return {"status": status, "msg": msg, "data": dict(data or {})}


def _legacy_idempotency_error(_request: Request, _code: str, message: str) -> Mapping[str, Any]:
    return legacy_body(0, message)


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next: Any):
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
        attrs = {
            "service.name": profile()["service_id"],
            "request_id": request_id,
            "correlation_id": correlation_id,
            "http.request.method": request.method,
            "http.method": request.method,
            "url.path": request.url.path,
            "http.route": request.url.path,
        }
        _emit_trace(tracer, "http.request.start", attrs)
        with _span_context(otel_tracer, f"{request.method} {request.url.path}") as span:
            for key, value in attrs.items():
                _set_span_attribute(span, key, value)
            response = await call_next(request)
            response.headers[REQUEST_ID_HEADER] = request_id
            response.headers[CORRELATION_ID_HEADER] = correlation_id
            _set_span_attribute(span, "http.response.status_code", response.status_code)
            _emit_trace(tracer, "http.request.complete", {**attrs, "status_code": response.status_code})
            return response

    @app.get("/healthz")
    def healthz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/readyz")
    def readyz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/health")
    def health_endpoint() -> dict[str, object]:
        return {"status": health(), "service": profile()}

    @app.get("/live")
    def live_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/ready")
    def ready_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]:
        return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}


def configure_legacy_routes(app: FastAPI, service: LegacyAclService) -> None:
    app.state.legacy_acl_service = service

    async def invoke(request: Request, operation: str) -> JSONResponse:
        operator = request.headers.get(LEGACY_OPERATOR_HEADER)
        if not operator or not operator.strip():
            return JSONResponse(status_code=400, content=legacy_body(0, "X-Legacy-Operator header is required"))
        source_ref = request.headers.get(IDEMPOTENCY_KEY_HEADER, "")
        ctx = LegacyContext(
            operator_ref=operator.strip(),
            reason=request.headers.get(LEGACY_REASON_HEADER),
            source_ref=source_ref,
            correlation_id=str(getattr(request.state, "correlation_id", None) or prefixed_uuid7("corr")),
        )
        try:
            payload = await request.json()
        except Exception:
            payload = {}
        if not isinstance(payload, Mapping):
            payload = {}
        result = getattr(service, operation)(payload, ctx)
        return JSONResponse(status_code=200, content=legacy_body(result.status, result.msg, result.data))

    @app.post("/api/v1/legacy/preserve")
    async def preserve(request: Request) -> JSONResponse:
        return await invoke(request, "preserve")

    @app.post("/api/v1/legacy/inside_payment")
    async def inside_payment(request: Request) -> JSONResponse:
        return await invoke(request, "inside_payment")

    @app.post("/api/v1/legacy/ticket_issue")
    async def ticket_issue(request: Request) -> JSONResponse:
        return await invoke(request, "ticket_issue")

    @app.post("/api/v1/legacy/execute")
    async def execute(request: Request) -> JSONResponse:
        return await invoke(request, "execute")

    @app.post("/api/v1/legacy/cancel")
    async def cancel(request: Request) -> JSONResponse:
        return await invoke(request, "cancel")

    @app.post("/api/v1/legacy/rebook")
    async def rebook(request: Request) -> JSONResponse:
        return await invoke(request, "rebook")


def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    client: DownstreamClient | None = None,
    event_publisher: Any | None = None,
    idempotency_store: IdempotencyStore | None = None,
) -> FastAPI:
    app = FastAPI(title="Legacy ACL", version="0.1.0")
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    configure_idempotency_middleware(
        app,
        idempotency_store or BoundedInMemoryIdempotencyStore(),
        require_key=True,
        include_path_prefixes=("/api/v1/legacy/",),
        error_body_factory=_legacy_idempotency_error,
    )
    configure_legacy_routes(app, LegacyAclService(client=client, publisher=event_publisher))
    return app
