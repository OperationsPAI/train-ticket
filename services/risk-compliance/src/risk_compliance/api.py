from collections.abc import Callable, Mapping
from typing import Any
from uuid import uuid4

from fastapi import FastAPI, Request

from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-ID"
CORRELATION_ID_HEADER = "X-Correlation-ID"
TraceHook = Callable[[str, Mapping[str, object]], None]


def _emit_trace(tracer: TraceHook | None, event: str, attributes: Mapping[str, object]) -> None:
    if tracer is not None:
        tracer(event, dict(attributes))


def _request_identifiers(request: Request) -> tuple[str, str]:
    request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid4())
    correlation_id = request.headers.get(CORRELATION_ID_HEADER) or request_id
    return request_id, correlation_id


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None) -> None:
    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next: Any):
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
        trace_attributes = {
            "request_id": request_id,
            "correlation_id": correlation_id,
            "method": request.method,
            "path": request.url.path,
        }
        _emit_trace(tracer, "http.request.start", trace_attributes)
        try:
            response = await call_next(request)
        except Exception as exc:  # pragma: no cover - exercised by FastAPI exception handling paths
            _emit_trace(tracer, "http.request.error", {**trace_attributes, "error": exc.__class__.__name__})
            raise
        response.headers[REQUEST_ID_HEADER] = request_id
        response.headers[CORRELATION_ID_HEADER] = correlation_id
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

    @app.get("/metadata")
    def metadata_endpoint() -> dict[str, object]:
        return {"service": profile(), "observability": {"tracing": "opt-in", "default": "noop"}}


def create_app(tracer: TraceHook | None = None) -> FastAPI:
    app = FastAPI(title='Risk & Compliance', version="0.1.0")
    configure_runtime_endpoints(app, tracer)
    return app
