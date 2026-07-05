from __future__ import annotations

from collections.abc import Callable, Mapping
import json
from contextlib import AbstractContextManager, nullcontext
from datetime import datetime, timedelta
from hashlib import sha256
from typing import Any, Protocol

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .application import search_itineraries, search_itineraries_from_payload
from .domain import AvailabilityHint, Itinerary, LegCandidate, PriceHint, TripIntent, TripPlanningValidationError
from .application_ports import EventPublisher
from .events import PublishFailed, build_itinerary_proposed_event, new_uuid7
from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-Id"
CORRELATION_ID_HEADER = "X-Correlation-Id"
IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"
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
    request_id = request.headers.get(REQUEST_ID_HEADER) or new_uuid7()
    correlation_id = request.headers.get(CORRELATION_ID_HEADER) or new_uuid7()
    return request_id, correlation_id


def _canonical_error(
    status_code: int,
    code: str,
    message: str,
    correlation_id: str,
    details: dict[str, object] | None = None,
) -> JSONResponse:
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
            _emit_trace(tracer, "http.request.complete", {**trace_attributes, "status_code": response.status_code})
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


def _require_string(payload: Mapping[str, object], field_name: str) -> str:
    value = payload.get(field_name)
    if not isinstance(value, str) or not value.strip():
        raise TripPlanningValidationError(f"{field_name} is required")
    return value.strip()


def _validate_contract_search_payload(payload: Mapping[str, object]) -> tuple[str, str, str, list[str], str, int]:
    origin_ref = _require_string(payload, "originRef")
    destination_ref = _require_string(payload, "destinationRef")
    departure_date = _require_string(payload, "departureDate")
    return_date_value = payload.get("returnDate")
    if return_date_value is not None and not isinstance(return_date_value, str):
        raise TripPlanningValidationError("returnDate must be an ISO-8601 date")
    traveler_refs_value = payload.get("travelerRefs")
    if not isinstance(traveler_refs_value, list) or not traveler_refs_value:
        raise TripPlanningValidationError("travelerRefs must be a non-empty array")
    traveler_refs = []
    for traveler_ref in traveler_refs_value:
        if not isinstance(traveler_ref, str) or not traveler_ref.strip():
            raise TripPlanningValidationError("travelerRefs must contain only strings")
        traveler_refs.append(traveler_ref.strip())
    channel = _require_string(payload, "channel")
    max_results_value = payload.get("maxResults", 10)
    if not isinstance(max_results_value, int) or isinstance(max_results_value, bool):
        raise TripPlanningValidationError("maxResults must be an integer")
    if max_results_value < 1 or max_results_value > 50:
        raise TripPlanningValidationError("maxResults must be between 1 and 50")
    try:
        datetime.fromisoformat(departure_date)
    except ValueError as exc:
        raise TripPlanningValidationError("departureDate must be an ISO-8601 date") from exc
    if return_date_value:
        try:
            datetime.fromisoformat(return_date_value)
        except ValueError as exc:
            raise TripPlanningValidationError("returnDate must be an ISO-8601 date") from exc
    return origin_ref, destination_ref, departure_date, traveler_refs, channel, max_results_value


def _contract_candidate(origin_ref: str, destination_ref: str, departure_date: str, channel: str) -> Itinerary:
    departure_time = datetime.fromisoformat(f"{departure_date}T09:00:00+00:00")
    arrival_time = departure_time + timedelta(hours=1)
    normalized_channel = "".join(ch for ch in channel.lower() if ch.isalnum() or ch in "-_") or "default"
    service_plan_ref = f"sp-{normalized_channel}-{departure_date}"
    route_digest = sha256(f"{origin_ref}|{destination_ref}".encode("utf-8")).hexdigest()[:12]
    service_segment_ref = f"seg-{normalized_channel}-{departure_date}-{route_digest}"
    return Itinerary(
        legs=(
            LegCandidate(
                service_plan_ref=service_plan_ref,
                service_segment_ref=service_segment_ref,
                origin_stop_ref=origin_ref,
                destination_stop_ref=destination_ref,
                departure_time=departure_time,
                arrival_time=arrival_time,
                mode="train",
                stop_refs=(origin_ref, destination_ref),
                segment_refs=(service_segment_ref,),
            ),
        ),
        price_hint=PriceHint(
            amount_minor=0,
            currency="CNY",
            snapshot_ref=f"fare-snapshot:{service_segment_ref}",
            captured_at=departure_time,
            confidence=50,
        ),
        availability_hint=AvailabilityHint(
            status="UNKNOWN",
            snapshot_ref=f"availability-snapshot:{service_segment_ref}",
            captured_at=departure_time,
            confidence=50,
        ),
        planning_snapshot_refs=(f"planning-snapshot:{service_segment_ref}",),
    )


def _itinerary_to_contract(itinerary: Itinerary) -> dict[str, object]:
    return {
        "itineraryRef": itinerary.itinerary_ref,
        "legs": [
            {
                "servicePlanRef": leg.service_plan_ref,
                "serviceSegmentRef": leg.service_segment_ref,
                "originStopRef": leg.origin_stop_ref,
                "destinationStopRef": leg.destination_stop_ref,
                "departureTime": leg.departure_time.isoformat().replace("+00:00", "Z"),
                "arrivalTime": leg.arrival_time.isoformat().replace("+00:00", "Z"),
                "mode": leg.mode,
            }
            for leg in itinerary.legs
        ],
        "priceHint": None if itinerary.price_hint is None else {
            "currency": itinerary.price_hint.currency,
            "minorUnits": itinerary.price_hint.amount_minor,
        },
        "availabilityHint": None if itinerary.availability_hint is None else {
            "status": itinerary.availability_hint.status,
            "confidence": itinerary.availability_hint.confidence,
        },
    }


def _search_contract_response(payload: Mapping[str, object]) -> tuple[dict[str, object], tuple[str, ...]]:
    origin_ref, destination_ref, departure_date, traveler_refs, channel, max_results = _validate_contract_search_payload(payload)
    intent = TripIntent(
        origin_ref=origin_ref,
        destination_ref=destination_ref,
        departure_window_start=f"{departure_date}T00:00:00Z",
        departure_window_end=f"{departure_date}T23:59:59Z",
        passenger_count=len(traveler_refs),
    )
    result = search_itineraries(intent, (_contract_candidate(origin_ref, destination_ref, departure_date, channel),))
    selected = result.candidates[:max_results]
    itineraries = [_itinerary_to_contract(itinerary) for itinerary, _score in selected]
    planning_snapshot_refs = tuple(ref for itinerary, _score in selected for ref in itinerary.planning_snapshot_refs)
    return {
        "intentRef": result.intent_ref,
        "itineraries": itineraries,
        "planningSnapshotRefs": list(planning_snapshot_refs),
    }, planning_snapshot_refs


def _idempotency_fingerprint(payload: Mapping[str, object]) -> str:
    return json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    event_publisher: EventPublisher | None = None,
) -> FastAPI:
    app = FastAPI(title="Trip Planning", version="0.1.0")
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    app.state.itineraries = {}
    app.state.idempotency_cache = {}

    @app.post("/search")
    def search_endpoint(payload: dict[str, object], request: Request) -> dict[str, object]:
        try:
            return search_itineraries_from_payload(payload)
        except TripPlanningValidationError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.post("/api/v1/itineraries/search")
    def search_itineraries_v1(payload: dict[str, object], request: Request) -> Any:
        """Search Itineraries — query endpoint; idempotency key is optional and replay-safe."""
        correlation_id: str = request.state.correlation_id
        idempotency_key = request.headers.get(IDEMPOTENCY_KEY_HEADER)
        fingerprint = _idempotency_fingerprint(payload)
        if idempotency_key:
            cached = app.state.idempotency_cache.get(idempotency_key)
            if cached is not None:
                cached_fingerprint, cached_response = cached
                if cached_fingerprint != fingerprint:
                    return _canonical_error(
                        422,
                        "IDEMPOTENCY_KEY_REUSED",
                        "Idempotency-Key was reused with a different request body",
                        correlation_id,
                    )
                return cached_response

        try:
            response, planning_snapshot_refs = _search_contract_response(payload)
        except TripPlanningValidationError as exc:
            return _canonical_error(400, "VALIDATION_FAILED", str(exc), correlation_id)

        for itinerary in response["itineraries"]:
            if isinstance(itinerary, dict):
                app.state.itineraries[itinerary["itineraryRef"]] = itinerary
        if event_publisher is not None:
            try:
                event_publisher.publish(
                    build_itinerary_proposed_event(
                        str(response["intentRef"]),
                        tuple(response["itineraries"]),  # type: ignore[arg-type]
                        planning_snapshot_refs,
                        correlation_id=correlation_id,
                    )
                )
            except PublishFailed:
                return _canonical_error(503, "UNAVAILABLE", "Event bus is unavailable", correlation_id)
        if idempotency_key:
            app.state.idempotency_cache[idempotency_key] = (fingerprint, response)
        return response

    @app.get("/api/v1/itineraries/{itineraryRef}")
    def get_itinerary(itineraryRef: str, request: Request) -> Any:
        correlation_id: str = request.state.correlation_id
        itinerary = app.state.itineraries.get(itineraryRef)
        if itinerary is None:
            return _canonical_error(404, "NOT_FOUND", f"Itinerary {itineraryRef} not found", correlation_id)
        return itinerary

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", new_uuid7())
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

    @app.exception_handler(RequestValidationError)
    async def request_validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", new_uuid7())
        return _canonical_error(400, "VALIDATION_FAILED", "Request validation failed", correlation_id, {"errors": exc.errors()})

    @app.exception_handler(TripPlanningValidationError)
    async def validation_exception_handler(request: Request, exc: TripPlanningValidationError) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", new_uuid7())
        return _canonical_error(400, "VALIDATION_FAILED", str(exc), correlation_id)

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        correlation_id: str = getattr(request.state, "correlation_id", new_uuid7())
        return _canonical_error(500, "UNAVAILABLE", "Internal server error", correlation_id)

    return app
