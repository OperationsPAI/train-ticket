from __future__ import annotations

import json
from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, asynccontextmanager, nullcontext
from hashlib import sha256
from typing import Any, Protocol
from fastapi import FastAPI, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response

from .application.service import ReportingApplicationService, RebuildRun, rfc3339_utc
from .domain import DashboardReadModel, MetricCategory, MetricDefinition, ReportingError
from .ids import is_uuid7, uuid7
from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-ID"
CORRELATION_ID_HEADER = "X-Correlation-ID"
TraceHook = Callable[[str, Mapping[str, object]], None]


class RuntimeSpan(AbstractContextManager["RuntimeSpan"], Protocol):
    def set_attribute(self, key: str, value: object) -> None: ...

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> bool | None: ...


class RuntimeTracer(Protocol):
    def start_as_current_span(self, name: str) -> RuntimeSpan: ...


class ApiError(Exception):
    def __init__(self, status_code: int, code: str, message: str, details: Mapping[str, object] | None = None) -> None:
        self.status_code = status_code
        self.code = code
        self.message = message
        self.details = dict(details or {})


class IdempotencyStore:
    def __init__(self) -> None:
        self._entries: dict[str, tuple[str, int, dict[str, str], Any]] = {}

    def lookup(self, key: str, fingerprint: str) -> tuple[int, dict[str, str], Any] | None:
        entry = self._entries.get(key)
        if entry is None:
            return None
        stored_fingerprint, status_code, headers, body = entry
        if stored_fingerprint != fingerprint:
            raise ApiError(422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key reused with a different request body")
        return status_code, dict(headers), body

    def store(self, key: str, fingerprint: str, status_code: int, headers: Mapping[str, str], body: Any) -> None:
        self._entries[key] = (fingerprint, status_code, dict(headers), body)


def _request_fingerprint(request: Request, body: bytes) -> str:
    material = b"\n".join(
        [
            request.method.upper().encode("utf-8"),
            str(request.url.path).encode("utf-8"),
            str(request.url.query).encode("utf-8"),
            body,
        ]
    )
    return sha256(material).hexdigest()


async def _response_body(response: Response) -> bytes:
    body = b""
    async for chunk in response.body_iterator:  # type: ignore[attr-defined]
        body += chunk
    return body


def _emit_trace(tracer: TraceHook | None, event: str, attributes: Mapping[str, object]) -> None:
    if tracer is not None:
        tracer(event, dict(attributes))


def opentelemetry_tracer_from_env(service_name: str) -> RuntimeTracer | None:
    """Return an OpenTelemetry API tracer only when OTEL_TRACES_EXPORTER is enabled."""
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
    request_id = request.headers.get(REQUEST_ID_HEADER) or uuid7()
    correlation_id = request.headers.get(CORRELATION_ID_HEADER) or request_id
    return request_id, correlation_id


def _ensure_request_context(request: Request) -> dict[str, str]:
    request_id = getattr(request.state, "request_id", None)
    correlation_id = getattr(request.state, "correlation_id", None)
    if request_id is None or correlation_id is None:
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
    return {REQUEST_ID_HEADER: request_id, CORRELATION_ID_HEADER: correlation_id}


def _error_body(request: Request, code: str, message: str, details: Mapping[str, object] | None = None) -> dict[str, object]:
    correlation_id = getattr(request.state, "correlation_id", None) or request.headers.get(CORRELATION_ID_HEADER, "")
    return {
        "code": code,
        "message": message,
        "correlationId": correlation_id,
        "details": dict(details or {}),
    }


def _enum_value(value: Any) -> Any:
    return getattr(value, "value", value)


def metric_to_json(metric: MetricDefinition) -> dict[str, object]:
    return {
        "metricId": metric.metric_id,
        "name": metric.name,
        "description": metric.description,
        "owner": metric.owner,
        "category": _enum_value(metric.category),
        "granularity": _enum_value(metric.granularity),
        "version": metric.version,
        "expression": metric.expression,
    }


def dashboard_to_json(dashboard: DashboardReadModel) -> dict[str, object]:
    snapshot = dashboard.current_snapshot
    return {
        "dashboardId": dashboard.dashboard_id,
        "name": dashboard.name,
        "description": dashboard.description,
        "status": _enum_value(dashboard.status),
        "metrics": [
            {"metricId": metric_ref.metric_id, "version": metric_ref.version}
            for metric_ref in dashboard.metrics
        ],
        "currentSnapshot": None
        if snapshot is None
        else {
            "rebuildId": snapshot.rebuild_id,
            "rebuiltAt": rfc3339_utc(snapshot.rebuilt_at),
            "metrics": [
                {"metricId": metric_ref.metric_id, "version": metric_ref.version}
                for metric_ref in snapshot.metrics
            ],
            "eventCount": snapshot.event_count,
            "digest": snapshot.digest,
        },
        "lastBuiltAt": None if dashboard.last_built_at is None else rfc3339_utc(dashboard.last_built_at),
        "sourceEvents": list(dashboard.source_events),
    }


def rebuild_to_json(run: RebuildRun) -> dict[str, object]:
    return {
        "rebuildId": run.rebuild_id,
        "dashboardId": run.dashboard_id,
        "rebuiltAt": rfc3339_utc(run.rebuilt_at),
        "status": run.status,
        "eventCount": run.event_count,
        "digest": run.digest,
    }


def _paginated(items: list[dict[str, object]], total: int, limit: int, offset: int) -> dict[str, object]:
    return {"items": items, "total": total, "limit": limit, "offset": offset}


def _validate_pagination(limit: int, offset: int) -> None:
    if limit < 1 or limit > 100:
        raise ApiError(400, "VALIDATION_FAILED", "limit must be between 1 and 100", {"field": "limit"})
    if offset < 0:
        raise ApiError(400, "VALIDATION_FAILED", "offset must be greater than or equal to 0", {"field": "offset"})


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
            except Exception as exc:  # pragma: no cover - exercised by exception handlers
                _set_span_attribute(span, "error.type", exc.__class__.__name__)
                _emit_trace(tracer, "http.request.error", {**trace_attributes, "error": exc.__class__.__name__})
                raise
            response.headers[REQUEST_ID_HEADER] = request_id
            response.headers[CORRELATION_ID_HEADER] = correlation_id
            _set_span_attribute(span, "http.response.status_code", response.status_code)
            _set_span_attribute(span, "http.status_code", response.status_code)
            _emit_trace(tracer, "http.request.complete", {**trace_attributes, "status_code": response.status_code})
            return response

    @app.middleware("http")
    async def idempotency_middleware(request: Request, call_next: Any):
        if request.method.upper() != "POST":
            return await call_next(request)
        key = request.headers.get("Idempotency-Key")
        if not key or not is_uuid7(key):
            return JSONResponse(
                status_code=400,
                content=_error_body(request, "VALIDATION_FAILED", "Idempotency-Key header must be a UUID v7"),
                headers=_ensure_request_context(request),
            )
        body = await request.body()
        fingerprint = _request_fingerprint(request, body)
        store: IdempotencyStore = request.app.state.idempotency_store
        try:
            replay = store.lookup(key, fingerprint)
        except ApiError as exc:
            return JSONResponse(
                status_code=exc.status_code,
                content=_error_body(request, exc.code, exc.message, exc.details),
                headers=_ensure_request_context(request),
            )
        if replay is not None:
            status_code, headers, cached_body = replay
            return JSONResponse(status_code=status_code, content=cached_body, headers={**headers, **_ensure_request_context(request)})
        response = await call_next(request)
        response_body = await _response_body(response)
        try:
            cached_body = json.loads(response_body.decode("utf-8")) if response_body else None
        except json.JSONDecodeError:
            cached_body = response_body.decode("utf-8")
        headers = {
            name: value
            for name, value in response.headers.items()
            if name.lower() in {"content-type"}
        }
        store.store(key, fingerprint, response.status_code, headers, cached_body)
        return Response(
            content=response_body,
            status_code=response.status_code,
            headers=dict(response.headers),
            media_type=response.media_type,
            background=response.background,
        )

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


def configure_reporting_endpoints(app: FastAPI, service: ReportingApplicationService) -> None:
    @app.get("/api/v1/metrics")
    def list_metrics(
        category: str | None = None,
        limit: int = Query(20),
        offset: int = Query(0),
    ) -> dict[str, object]:
        _validate_pagination(limit, offset)
        metric_category = None
        if category is not None:
            try:
                metric_category = MetricCategory(category)
            except ValueError as exc:
                raise ApiError(400, "VALIDATION_FAILED", "category is invalid", {"field": "category"}) from exc
        items, total = service.list_metrics(category=metric_category, limit=limit, offset=offset)
        return _paginated([metric_to_json(metric) for metric in items], total, limit, offset)

    @app.get("/api/v1/metrics/{metric_id}")
    def get_metric(metric_id: str) -> dict[str, object]:
        metric = service.get_metric(metric_id)
        if metric is None:
            raise ApiError(404, "NOT_FOUND", "metric was not found", {"metricId": metric_id})
        return metric_to_json(metric)

    @app.get("/api/v1/dashboards/{dashboard_id}")
    def get_dashboard(dashboard_id: str) -> dict[str, object]:
        dashboard = service.get_dashboard(dashboard_id)
        if dashboard is None:
            raise ApiError(404, "NOT_FOUND", "dashboard was not found", {"dashboardId": dashboard_id})
        return dashboard_to_json(dashboard)

    @app.get("/api/v1/dashboards/{dashboard_id}/rebuilds")
    def list_dashboard_rebuilds(
        dashboard_id: str,
        limit: int = Query(20),
        offset: int = Query(0),
    ) -> dict[str, object]:
        _validate_pagination(limit, offset)
        if service.get_dashboard(dashboard_id) is None:
            raise ApiError(404, "NOT_FOUND", "dashboard was not found", {"dashboardId": dashboard_id})
        items, total = service.list_rebuilds(dashboard_id, limit=limit, offset=offset)
        return _paginated([rebuild_to_json(run) for run in items], total, limit, offset)


def configure_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error_handler(request: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse(status_code=exc.status_code, content=_error_body(request, exc.code, exc.message, exc.details))

    @app.exception_handler(ReportingError)
    async def domain_error_handler(request: Request, exc: ReportingError) -> JSONResponse:
        return JSONResponse(status_code=422, content=_error_body(request, "DOMAIN_RULE_VIOLATION", str(exc)))

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        return JSONResponse(
            status_code=400,
            content=_error_body(request, "VALIDATION_FAILED", "request validation failed", {"errors": exc.errors()}),
        )


def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    service: ReportingApplicationService | None = None,
    enable_messaging: bool = False,
) -> FastAPI:
    app_service = service or ReportingApplicationService()
    messaging_runtime = None
    if enable_messaging:
        from .adapters.messaging.runtime import MessagingRuntime

        messaging_runtime = MessagingRuntime(app_service)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if messaging_runtime is not None:
            messaging_runtime.start()
        try:
            yield
        finally:
            if messaging_runtime is not None:
                messaging_runtime.stop()

    app = FastAPI(title="Reporting", version="0.1.0", lifespan=lifespan)
    configure_error_handlers(app)
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    configure_reporting_endpoints(app, app_service)
    app.state.reporting_service = app_service
    app.state.idempotency_store = IdempotencyStore()
    return app


def create_production_app() -> FastAPI:
    return create_app(enable_messaging=True)
