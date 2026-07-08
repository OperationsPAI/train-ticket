from __future__ import annotations

from collections.abc import Callable, Mapping
from contextlib import AbstractContextManager, asynccontextmanager, nullcontext
from datetime import datetime, timedelta
import os
from pathlib import Path
from hashlib import sha256
import logging
import threading
from typing import Any, Protocol

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from train_ticket_platform.observability import init_opentelemetry
from .application import search_itineraries, search_itineraries_from_payload
from .domain import AvailabilityHint, Itinerary, LegCandidate, PriceHint, TripIntent, TripPlanningValidationError
from .application_ports import EventPublisher, EventSubscriber
from .events import PublishFailed, build_itinerary_proposed_event, new_uuid7
from train_ticket_platform.idempotency import configure_idempotency_middleware
from train_ticket_platform.storage import DatabaseConfig, DatabasePool, OptimisticConcurrencyError, OutboxRelay, PostgresIdempotencyStore, ReadinessGate, run_migrations

from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-Id"
CORRELATION_ID_HEADER = "X-Correlation-Id"
TraceHook = Callable[[str, Mapping[str, object]], None]
logger = logging.getLogger(__name__)


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


def _configure_postgres(app: FastAPI) -> tuple[Any | None, Any | None, Any | None]:
    config = DatabaseConfig.from_env()
    if config is None:
        return None, None, None
    from .adapters.storage import PostgresPlanStore, TransactionalOutboxPublisher

    readiness = ReadinessGate()
    pool = DatabasePool(config)
    app.state.database_pool = pool
    app.state.readiness = readiness
    env_dir = os.environ.get("MIGRATIONS_DIR")
    migrations_dir = Path(env_dir) if env_dir else Path(__file__).resolve().parents[2] / "migrations"
    run_migrations(pool, migrations_dir, readiness)
    plan_store = PostgresPlanStore(pool)
    relay = OutboxRelay(pool)
    relay.start()
    app.state.outbox_relay = relay
    return plan_store, TransactionalOutboxPublisher(plan_store), PostgresIdempotencyStore(pool)

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


class PlanStore:
    """Leg-candidate source built from consumed service-plan and
    place-network events. Thread-safe; written by the subscriber thread,
    read by search requests."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.services: dict[str, dict[str, object]] = {}
        self.segments: dict[str, dict[str, object]] = {}
        self.node_place: dict[str, str] = {}
        self.applied_event_ids: set[str] = set()

    def clear(self) -> None:
        with self._lock:
            self.services.clear()
            self.segments.clear()
            self.node_place.clear()
            self.applied_event_ids.clear()

    def apply_envelope(self, envelope: Any) -> bool:
        event_id = str(getattr(envelope, "eventId", ""))
        with self._lock:
            if event_id and event_id in self.applied_event_ids:
                return False
            self._apply_locked(str(envelope.eventType), envelope.payload)
            if event_id:
                self.applied_event_ids.add(event_id)
        return True

    def apply(self, event_type: str, payload: Mapping[str, object]) -> None:
        with self._lock:
            self._apply_locked(event_type, payload)

    def load(self) -> None:
        return None

    def _apply_locked(self, event_type: str, payload: Mapping[str, object]) -> None:
        if event_type in ("ServicePlanPublished", "ScheduledServiceCreated"):
            ref = str(payload.get("scheduledServiceRef", ""))
            if ref:
                self.services[ref] = dict(payload)
        elif event_type in ("ServicePlanChanged", "ServiceSegmentCreated"):
            seg = str(payload.get("segmentRef", ""))
            if seg and payload.get("originStopRef"):
                self.segments[seg] = dict(payload)
        elif event_type in ("TransportNodeRegistered", "TransportNodeAdded", "TransportNodeUpdated"):
            node = str(payload.get("nodeId", ""))
            place = str(payload.get("placeId", ""))
            if node and place:
                self.node_place[node] = place

    def _matches(self, stop_ref: str, requested: str) -> bool:
        requested_place = self.node_place.get(requested)
        stop_place = self.node_place.get(stop_ref)
        return (
            stop_ref == requested
            or stop_place == requested
            or (requested_place is not None and stop_ref == requested_place)
            or (requested_place is not None and stop_place == requested_place)
        )

    def candidates(self, origin_ref: str, destination_ref: str, departure_date: str) -> list[Itinerary]:
        found: list[Itinerary] = []
        with self._lock:
            for seg_ref, seg in self.segments.items():
                origin_stop = str(seg.get("originStopRef", ""))
                destination_stop = str(seg.get("destinationStopRef", ""))
                departure_raw = str(seg.get("departureTime", ""))
                if not departure_raw.startswith(departure_date):
                    continue
                if not (self._matches(origin_stop, origin_ref) and self._matches(destination_stop, destination_ref)):
                    continue
                departure_time = datetime.fromisoformat(departure_raw.replace("Z", "+00:00"))
                arrival_raw = str(seg.get("arrivalTime", departure_raw))
                arrival_time = datetime.fromisoformat(arrival_raw.replace("Z", "+00:00"))
                service_ref = str(seg.get("scheduledServiceRef", ""))
                found.append(Itinerary(
                    legs=(
                        LegCandidate(
                            service_plan_ref=service_ref,
                            service_segment_ref=seg_ref,
                            origin_stop_ref=origin_stop,
                            destination_stop_ref=destination_stop,
                            departure_time=departure_time,
                            arrival_time=arrival_time,
                            mode="train",
                            stop_refs=(origin_stop, destination_stop),
                            segment_refs=(seg_ref,),
                        ),
                    ),
                    price_hint=PriceHint(
                        amount_minor=0,
                        currency="CNY",
                        snapshot_ref=f"fare-snapshot:{seg_ref}",
                        captured_at=departure_time,
                        confidence=50,
                    ),
                    availability_hint=AvailabilityHint(
                        status="UNKNOWN",
                        snapshot_ref=f"availability-snapshot:{seg_ref}",
                        captured_at=departure_time,
                        confidence=50,
                    ),
                    planning_snapshot_refs=(f"planning-snapshot:{seg_ref}",),
                ))
        return found


_plan_store = PlanStore()
_active_plan_store: Any = _plan_store


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


def _candidate_for_intent(candidate: Itinerary, origin_ref: str, destination_ref: str) -> Itinerary:
    if candidate.origin_ref == origin_ref and candidate.destination_ref == destination_ref:
        return candidate
    return Itinerary(
        legs=candidate.legs,
        itinerary_ref=candidate.itinerary_ref,
        price_hint=candidate.price_hint,
        availability_hint=candidate.availability_hint,
        planning_snapshot_refs=candidate.planning_snapshot_refs,
        search_origin_ref=origin_ref,
        search_destination_ref=destination_ref,
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
    real_candidates = _active_plan_store.candidates(origin_ref, destination_ref, departure_date)
    candidate_pool = (
        tuple(_candidate_for_intent(candidate, origin_ref, destination_ref) for candidate in real_candidates)
        if real_candidates
        else (_contract_candidate(origin_ref, destination_ref, departure_date, channel),)
    )
    result = search_itineraries(intent, candidate_pool)
    selected = result.candidates[:max_results]
    itineraries = [_itinerary_to_contract(itinerary) for itinerary, _score in selected]
    planning_snapshot_refs = tuple(ref for itinerary, _score in selected for ref in itinerary.planning_snapshot_refs)
    return {
        "intentRef": result.intent_ref,
        "itineraries": itineraries,
        "planningSnapshotRefs": list(planning_snapshot_refs),
    }, planning_snapshot_refs


def _default_publisher() -> EventPublisher:
    from .adapters.messaging.redis_streams import RedisEventPublisher

    return RedisEventPublisher()


def _default_subscriber() -> EventSubscriber:
    from .adapters.messaging.redis_streams import create_redis_event_subscriber

    return create_redis_event_subscriber()


def _occurred_at_text(envelope: Any) -> str:
    occurred_at = getattr(envelope, "occurredAt", "")
    return occurred_at.isoformat() if hasattr(occurred_at, "isoformat") else str(occurred_at)


def _handle_upstream_event(app: FastAPI, envelope: Any) -> None:
    _active_plan_store.apply_envelope(envelope)
    app.state.consumed_events[envelope.eventId] = {
        "eventType": envelope.eventType,
        "producer": envelope.producer,
        "occurredAt": _occurred_at_text(envelope),
    }
    app.state.upstream_event_payloads[envelope.eventId] = dict(envelope.payload)


def _start_subscriber(subscriber: EventSubscriber, handler: Callable[[Any], None]) -> threading.Thread | None:
    from .adapters.messaging.subscriber import start_trip_planning_subscription

    return start_trip_planning_subscription(subscriber, handler)


def _load_plan_index_from_db() -> None:
    if hasattr(_active_plan_store, "load"):
        _active_plan_store.load()


def _stop_subscriber(subscriber: EventSubscriber | None, thread: threading.Thread | None) -> None:
    if subscriber is None:
        return
    stop = getattr(subscriber, "shutdown", None) or getattr(subscriber, "stop", None)
    if callable(stop):
        stop()
    if thread is not None:
        thread.join(timeout=5)


def create_app(
    tracer: TraceHook | None = None,
    otel_tracer: RuntimeTracer | None = None,
    event_publisher: EventPublisher | None = None,
    event_subscriber: EventSubscriber | None = None,
    start_event_subscriber: bool = True,
) -> FastAPI:
    global _active_plan_store
    postgres_plan_store: Any | None = None
    postgres_publisher: Any | None = None
    postgres_idempotency_store: Any | None = None
    publisher = event_publisher if event_publisher is not None else None
    subscriber = event_subscriber if event_subscriber is not None else None

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        active_subscriber = subscriber
        if active_subscriber is None and start_event_subscriber:
            active_subscriber = _default_subscriber()
        handler = lambda envelope: _handle_upstream_event(app, envelope)
        _load_plan_index_from_db()
        subscriber_thread = _start_subscriber(active_subscriber, handler) if active_subscriber is not None and start_event_subscriber else None
        app.state.event_subscriber = active_subscriber
        app.state.subscriber_thread = subscriber_thread
        try:
            yield
        finally:
            _stop_subscriber(getattr(app.state, "event_subscriber", None), subscriber_thread)
            relay = getattr(app.state, "outbox_relay", None)
            if relay is not None:
                relay.stop()
            pool = getattr(app.state, "database_pool", None)
            if pool is not None:
                pool.close()

    app = FastAPI(title="Trip Planning", version="0.1.0", lifespan=lifespan)
    init_opentelemetry(profile()["service_id"], app=app)
    postgres_plan_store, postgres_publisher, postgres_idempotency_store = _configure_postgres(app)
    _active_plan_store = postgres_plan_store or _plan_store
    if publisher is None:
        publisher = postgres_publisher or _default_publisher()
    configure_runtime_endpoints(app, tracer, otel_tracer or opentelemetry_tracer_from_env(profile()["service_id"]))
    app.state.itineraries = {}
    configure_idempotency_middleware(app, postgres_idempotency_store, require_key=False, include_path_prefixes=("/api/v1/itineraries/search",))
    app.state.consumed_events = {}
    app.state.upstream_event_payloads = {}

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
        try:
            response, planning_snapshot_refs = _search_contract_response(payload)
        except TripPlanningValidationError as exc:
            return _canonical_error(400, "VALIDATION_FAILED", str(exc), correlation_id)

        event = build_itinerary_proposed_event(
            str(response["intentRef"]),
            tuple(response["itineraries"]),  # type: ignore[arg-type]
            planning_snapshot_refs,
            correlation_id=correlation_id,
        )
        transaction = getattr(_active_plan_store, "transaction", None)
        try:
            context = transaction() if callable(transaction) else nullcontext()
            with context:
                for itinerary in response["itineraries"]:
                    if isinstance(itinerary, dict):
                        app.state.itineraries[itinerary["itineraryRef"]] = itinerary
                        save_itinerary = getattr(_active_plan_store, "save_itinerary", None)
                        if callable(save_itinerary):
                            try:
                                save_itinerary(itinerary)
                            except OptimisticConcurrencyError:
                                logger.info(
                                    "itinerary snapshot write lost deterministic-id race",
                                    extra={"itinerary_ref": itinerary["itineraryRef"]},
                                )
                publisher.publish(event)
        except PublishFailed:
            return _canonical_error(503, "UNAVAILABLE", "Event bus is unavailable", correlation_id)
        return response

    @app.get("/api/v1/itineraries/{itineraryRef}")
    def get_itinerary(itineraryRef: str, request: Request) -> Any:
        correlation_id: str = request.state.correlation_id
        itinerary = app.state.itineraries.get(itineraryRef)
        if itinerary is None:
            get_itinerary = getattr(_active_plan_store, "get_itinerary", None)
            if callable(get_itinerary):
                itinerary = get_itinerary(itineraryRef)
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
