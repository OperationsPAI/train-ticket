from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, nullcontext
from typing import Any, Protocol
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from .application import search_itineraries_from_payload
from .domain import TripPlanningValidationError
from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-ID"
CORRELATION_ID_HEADER = "X-Correlation-ID"
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
    request_id = request.headers.get(REQUEST_ID_HEADER) or str(uuid4())
    correlation_id = request.headers.get(CORRELATION_ID_HEADER) or request_id
    return request_id, correlation_id


def _canonical_error(status_code: int, code: str, message: str, correlation_id: str, details: dict[str, object] | None = None) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={
            "code": code,
            "message": message,
            "correlationId": correlation_id,
            "details": details or {},
        },
    )


def configure_runtime_endpoints(app: FastAPI, tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> None:
    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next: Any):
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
        service_name = profile()["service_id"]
        path = request.url.path
        trace_attributes: dict[str, object] = {
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

    @app.get("/healthz")
    def healthz_endpoint() -> dict[str, object]:
        return {"status": health(), "service": profile()}

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


def create_app(tracer: TraceHook | None = None, otel_tracer: RuntimeTracer | None = None) -> FastAPI:
    app = FastAPI(title='Trip Planning', version="0.1.0")
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))

    # --- Existing /search endpoint (backward compat) ---
    @app.post("/search")
    def search_endpoint(payload: dict[str, object], request: Request) -> dict[str, object]:
        try:
            return search_itineraries_from_payload(payload)
        except TripPlanningValidationError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    # --- API v1 endpoints per docs/08-contracts/api/trip-planning.md ---

    @app.post("/api/v1/itineraries/search")
    def search_itineraries_v1(payload: dict[str, object], request: Request) -> Any:
        """POST /api/v1/itineraries/search

        Search Itineraries — query endpoint; idempotency key is not required.
        """
        correlation_id: str = request.state.correlation_id
        try:
            # Transform the contract-required fields into the internal payload format
            departure_date = payload.get("departureDate")
            departure_window_start = f"{departure_date}T00:00:00Z" if departure_date and isinstance(departure_date, str) else None
            departure_window_end = f"{departure_date}T23:59:59Z" if departure_date and isinstance(departure_date, str) else None

            traveler_refs = payload.get("travelerRefs", [])
            if not isinstance(traveler_refs, (list, tuple)):
                traveler_refs = []

            intent: dict[str, object] = {
                "originRef": payload.get("originRef"),
                "destinationRef": payload.get("destinationRef"),
                "departureWindowStart": departure_window_start,
                "departureWindowEnd": departure_window_end,
                "passengerCount": len(traveler_refs) or 1,
                "preferences": {},
            }

            search_payload: dict[str, object] = {
                "intent": intent,
                "candidates": [],
            }
            result = search_itineraries_from_payload(search_payload)
            return result
        except TripPlanningValidationError as exc:
            return _canonical_error(
                400,
                "VALIDATION_FAILED",
                str(exc),
                correlation_id,
            )

    @app.get("/api/v1/itineraries/{itinerary_ref}")
    def get_itinerary(itinerary_ref: str, request: Request) -> JSONResponse:
        """GET /api/v1/itineraries/{itineraryRef}

        Get Itinerary — returns full itinerary details.
        Trip Planning does not persist itineraries; always return NOT_FOUND.
        """
        correlation_id: str = request.state.correlation_id
        return _canonical_error(
            404,
            "NOT_FOUND",
            f"Itinerary {itinerary_ref} not found",
            correlation_id,
        )

    # Exception handlers for canonical error body
    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", str(uuid4()))
        code = "VALIDATION_FAILED"
        if exc.status_code == 404:
            code = "NOT_FOUND"
        elif exc.status_code == 409:
            code = "CONFLICT"
        elif exc.status_code == 412:
            code = "PRECONDITION_FAILED"
        elif exc.status_code == 422:
            code = "DOMAIN_RULE_VIOLATION"
        elif exc.status_code == 503:
            code = "UNAVAILABLE"
        return _canonical_error(exc.status_code, code, str(exc.detail), correlation_id)

    @app.exception_handler(TripPlanningValidationError)
    async def validation_exception_handler(request: Request, exc: TripPlanningValidationError) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", str(uuid4()))
        return _canonical_error(400, "VALIDATION_FAILED", str(exc), correlation_id)

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", str(uuid4()))
        return _canonical_error(500, "UNAVAILABLE", "Internal server error", correlation_id)

    return app
