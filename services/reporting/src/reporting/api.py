from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, asynccontextmanager, nullcontext
import os
from pathlib import Path
from typing import Any, Protocol
from fastapi import FastAPI, Query, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .application.service import ReportingApplicationService, RebuildRun, rfc3339_utc
from .domain import DashboardReadModel, MetricCategory, MetricDefinition, ReportingError
from train_ticket_platform.idempotency import configure_idempotency_middleware
from train_ticket_platform.storage import DatabaseConfig, DatabasePool, OutboxRelay, PostgresIdempotencyStore, ReadinessGate, run_migrations

from .ids import uuid7
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


    @app.get("/healthz")
    def healthz_endpoint() -> dict[str, str]:
        return {"status": health()}

    @app.get("/readyz")
    def readyz_endpoint(response: Response) -> dict[str, str]:
        if not _storage_ready(app):
            response.status_code = 503
            return {"status": "not-ready"}
        return {"status": health()}

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

    @app.exception_handler(StarletteHTTPException)
    async def framework_http_error_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code_by_status = {
            400: "VALIDATION_FAILED",
            404: "NOT_FOUND",
            405: "VALIDATION_FAILED",
            409: "CONFLICT",
            412: "PRECONDITION_FAILED",
            422: "DOMAIN_RULE_VIOLATION",
            503: "UNAVAILABLE",
        }
        code = code_by_status.get(exc.status_code, "UNAVAILABLE")
        message = exc.detail if isinstance(exc.detail, str) else "request failed"
        return JSONResponse(status_code=exc.status_code, content=_error_body(request, code, message))



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


def _postgres_service_from_env(app: FastAPI) -> tuple[ReportingApplicationService, Any | None]:
    config = DatabaseConfig.from_env()
    if config is None:
        return ReportingApplicationService(), None
    from .adapters.storage import PostgresReportingApplicationService

    readiness = ReadinessGate()
    pool = DatabasePool(config)
    app.state.database_pool = pool
    app.state.readiness = readiness
    env_dir = os.environ.get("MIGRATIONS_DIR")
    migrations_dir = Path(env_dir) if env_dir else Path(__file__).resolve().parents[2] / "migrations"
    run_migrations(pool, migrations_dir, readiness)
    service = PostgresReportingApplicationService(pool)
    relay = OutboxRelay(pool)
    relay.start()
    app.state.outbox_relay = relay
    return service, PostgresIdempotencyStore(pool)

def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    service: ReportingApplicationService | None = None,
    enable_messaging: bool = False,
) -> FastAPI:
    default_idempotency_store = None
    app_service = service
    messaging_runtime = None

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if messaging_runtime is not None:
            messaging_runtime.start()
        try:
            yield
        finally:
            if messaging_runtime is not None:
                messaging_runtime.stop()
            relay = getattr(_.state, "outbox_relay", None)
            if relay is not None:
                relay.stop()
            pool = getattr(_.state, "database_pool", None)
            if pool is not None:
                pool.close()

    app = FastAPI(title="Reporting", version="0.1.0", lifespan=lifespan)
    if app_service is None:
        app_service, default_idempotency_store = _postgres_service_from_env(app)
    if enable_messaging:
        from .adapters.messaging.runtime import MessagingRuntime
        messaging_runtime = MessagingRuntime(app_service)
    configure_error_handlers(app)
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    configure_reporting_endpoints(app, app_service)
    app.state.reporting_service = app_service
    configure_idempotency_middleware(app, default_idempotency_store, require_key=True, include_path_prefixes=("/api/v1/test-command",))
    return app


def create_production_app() -> FastAPI:
    return create_app(enable_messaging=True)
