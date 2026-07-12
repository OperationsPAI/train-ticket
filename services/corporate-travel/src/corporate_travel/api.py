from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.exceptions import HTTPException as StarletteHTTPException

from train_ticket_platform.messaging import InMemoryEventPublisher
from train_ticket_platform.observability import init_opentelemetry
from train_ticket_platform.storage import DatabaseConfig, DatabasePool, OutboxRelay, run_migrations

from train_ticket_platform.ids import is_uuid7

from .application import AgreementNotFoundError, CorporateTravelService, InMemoryCorporateTravelRepository, PostgresCorporateTravelRepository, uuid7
from .domain import CorporateTravelError
from .runtime import health, profile

REQUEST_ID_HEADER = "X-Request-ID"
CORRELATION_ID_HEADER = "X-Correlation-ID"

_default_service: CorporateTravelService | None = None


def get_default_service() -> CorporateTravelService:
    global _default_service
    if _default_service is None:
        _default_service = CorporateTravelService(publisher=InMemoryEventPublisher(), repository=InMemoryCorporateTravelRepository())
    return _default_service


def set_default_service(service: CorporateTravelService) -> None:
    global _default_service
    _default_service = service


class MoneyModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    currency: str = Field(min_length=3, max_length=3)
    minorUnits: int = Field(ge=0)


class EffectiveWindowModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    startsAt: str = Field(min_length=1)
    endsAt: str = Field(min_length=1)


class PriceRefModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    fareRuleRefs: list[str] = Field(default_factory=list)
    ruleSetId: str | None = None
    ruleSetVersion: str | None = None


class BillingCalendarModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    billingPeriod: str = Field(min_length=1)
    cutoffAt: str = Field(min_length=1)
    dueAt: str = Field(min_length=1)
    timezonePolicy: str = "UTC"


class CreateAgreementRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    corporateId: str = Field(min_length=1)
    agreementCode: str = Field(min_length=1)
    legalName: str = Field(min_length=1)
    effectiveWindow: EffectiveWindowModel
    priceRef: PriceRefModel
    monthlyCreditLimit: MoneyModel
    billingCalendar: BillingCalendarModel
    contact: dict[str, str] = Field(default_factory=dict)
    activate: bool = True


class AuthorizeTravelerRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    accountId: str = Field(min_length=1)
    costCenter: str = Field(min_length=1)
    projectCode: str | None = None
    scope: dict[str, Any] = Field(default_factory=dict)
    maxTripAmount: MoneyModel | None = None
    canDelegateBooking: bool = False
    validFrom: str | None = None
    validUntil: str | None = None




class PolicyCheckRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    employeeRef: str = Field(min_length=1)
    departmentRef: str = Field(min_length=1)
    origin: str = Field(min_length=1)
    destination: str = Field(min_length=1)
    seatClass: str = "SECOND_CLASS"
    amount: MoneyModel
    requestedAt: str = Field(min_length=1)
    departureAt: str = Field(min_length=1)
    tripDurationMinutes: int = Field(ge=0)
    employeeLevel: str = "STAFF"
    managerRef: str | None = None
    emergency: bool = False
    bookingRef: str | None = None


class InvoiceLineItemModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    bookingRef: str = Field(min_length=1)
    employeeName: str = Field(min_length=1)
    route: str = Field(min_length=1)
    travelDate: str = Field(min_length=1)
    amountMinor: int = Field(ge=0)


class GenerateInvoiceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    period: str = Field(min_length=1)
    lineItems: list[InvoiceLineItemModel]
    discountRateBps: int = Field(default=0, ge=0, le=10000)


class ErrorBody(BaseModel):
    code: str
    message: str
    correlationId: str
    details: dict[str, Any]


def _request_identifiers(request: Request) -> tuple[str, str]:
    request_id = str(uuid7())
    supplied = request.headers.get("x-correlation-id") or request.headers.get("X-Correlation-ID")
    correlation_id = supplied if supplied and is_uuid7(supplied) else str(uuid7())
    return request_id, correlation_id


def error_response(request: Request, status_code: int, code: str, message: str, details: dict[str, Any] | None = None) -> JSONResponse:
    correlation_id = getattr(request.state, "correlation_id", str(uuid7()))
    return JSONResponse(
        status_code=status_code,
        content=ErrorBody(code=code, message=message, correlationId=correlation_id, details=details or {}).model_dump(),
    )


def configure_runtime_endpoints(app: FastAPI) -> None:
    @app.middleware("http")
    async def request_context_middleware(request: Request, call_next: Any):
        request_id, correlation_id = _request_identifiers(request)
        request.state.request_id = request_id
        request.state.correlation_id = correlation_id
        response = await call_next(request)
        response.headers[REQUEST_ID_HEADER] = request_id
        response.headers[CORRELATION_ID_HEADER] = correlation_id
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
        return {"service": profile()}


def configure_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        return error_response(request, 400, "VALIDATION_FAILED", "Request validation failed", {"errors": exc.errors()})

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        if exc.status_code == 404:
            return error_response(request, 404, "NOT_FOUND", "The requested resource was not found")
        return error_response(request, exc.status_code, "UNAVAILABLE", str(exc.detail))


def configure_corporate_travel_endpoints(app: FastAPI, service: CorporateTravelService) -> None:
    @app.post("/agreements", status_code=201, response_model=None)
    @app.post("/api/v1/agreements", status_code=201, response_model=None)
    def create_agreement(request: Request, payload: CreateAgreementRequest) -> JSONResponse:
        try:
            result = service.create_agreement(
                corporate_id=payload.corporateId,
                agreement_code=payload.agreementCode,
                legal_name=payload.legalName,
                effective_window=payload.effectiveWindow.model_dump(),
                price_ref=payload.priceRef.model_dump(),
                monthly_credit_limit=payload.monthlyCreditLimit.model_dump(),
                billing_calendar=payload.billingCalendar.model_dump(),
                contact=payload.contact,
                activate=payload.activate,
                correlation_id=request.state.correlation_id,
                causation_id=request.headers.get("Idempotency-Key") or request.state.request_id,
            )
        except CorporateTravelError as exc:
            return error_response(request, 400, "CORPORATE_TRAVEL_INVARIANT_VIOLATION", str(exc))
        return JSONResponse(status_code=201, content=result.to_dict())

    @app.get("/agreements/{agreementId}", response_model=None)
    @app.get("/api/v1/agreements/{agreementId}", response_model=None)
    def get_agreement(request: Request, agreementId: str) -> dict[str, Any] | JSONResponse:
        try:
            return service.get_agreement(agreementId).to_dict()
        except AgreementNotFoundError:
            return error_response(request, 404, "NOT_FOUND", "Corporate agreement was not found")

    @app.post("/agreements/{agreementId}/authorize", status_code=201, response_model=None)
    @app.post("/api/v1/agreements/{agreementId}/authorize", status_code=201, response_model=None)
    def authorize_traveler(request: Request, agreementId: str, payload: AuthorizeTravelerRequest) -> JSONResponse:
        try:
            traveler = service.authorize_traveler(
                agreement_id=agreementId,
                account_id=payload.accountId,
                cost_center=payload.costCenter,
                project_code=payload.projectCode,
                scope=payload.scope,
                max_trip_amount=payload.maxTripAmount.model_dump() if payload.maxTripAmount else None,
                can_delegate_booking=payload.canDelegateBooking,
                valid_from=payload.validFrom,
                valid_until=payload.validUntil,
                correlation_id=request.state.correlation_id,
                causation_id=request.headers.get("Idempotency-Key") or request.state.request_id,
            )
        except AgreementNotFoundError:
            return error_response(request, 404, "NOT_FOUND", "Corporate agreement was not found")
        except CorporateTravelError as exc:
            return error_response(request, 400, "CORPORATE_TRAVEL_INVARIANT_VIOLATION", str(exc))
        return JSONResponse(status_code=201, content=traveler.to_dict())

    @app.post("/agreements/{agreementId}/policy-checks", status_code=201, response_model=None)
    @app.post("/api/v1/agreements/{agreementId}/policy-checks", status_code=201, response_model=None)
    def check_policy(request: Request, agreementId: str, payload: PolicyCheckRequest) -> JSONResponse:
        try:
            result = service.check_policy_and_reserve(
                agreement_id=agreementId,
                employee_ref=payload.employeeRef,
                department_ref=payload.departmentRef,
                origin=payload.origin,
                destination=payload.destination,
                seat_class=payload.seatClass,
                amount=payload.amount.model_dump(),
                requested_at=payload.requestedAt,
                departure_at=payload.departureAt,
                trip_duration_minutes=payload.tripDurationMinutes,
                employee_level=payload.employeeLevel,
                manager_ref=payload.managerRef,
                emergency=payload.emergency,
                booking_ref=payload.bookingRef,
                correlation_id=request.state.correlation_id,
                causation_id=request.headers.get("Idempotency-Key") or request.state.request_id,
            )
        except AgreementNotFoundError:
            return error_response(request, 404, "NOT_FOUND", "Corporate agreement was not found")
        except (CorporateTravelError, ValueError) as exc:
            return error_response(request, 400, "CORPORATE_TRAVEL_INVARIANT_VIOLATION", str(exc))
        return JSONResponse(status_code=201, content=result.to_dict())

    @app.post("/agreements/{agreementId}/monthly-invoices", status_code=201, response_model=None)
    @app.post("/api/v1/agreements/{agreementId}/monthly-invoices", status_code=201, response_model=None)
    def generate_invoice(request: Request, agreementId: str, payload: GenerateInvoiceRequest) -> JSONResponse:
        try:
            result = service.generate_monthly_invoice(
                agreement_id=agreementId,
                period=payload.period,
                line_items=[item.model_dump() for item in payload.lineItems],
                discount_rate_bps=payload.discountRateBps,
                correlation_id=request.state.correlation_id,
                causation_id=request.headers.get("Idempotency-Key") or request.state.request_id,
            )
        except AgreementNotFoundError:
            return error_response(request, 404, "NOT_FOUND", "Corporate agreement was not found")
        except CorporateTravelError as exc:
            return error_response(request, 400, "CORPORATE_TRAVEL_INVARIANT_VIOLATION", str(exc))
        return JSONResponse(status_code=201, content=result.to_dict())


def create_app(service: CorporateTravelService | None = None) -> FastAPI:
    import os
    from .messaging import (
        CORPORATE_TRAVEL_CONSUMER_GROUP,
        CORPORATE_TRAVEL_SUBSCRIPTIONS,
        RedisEventSubscriber,
        corporate_travel_consumer_name,
        build_event_handler,
    )

    subscriber: RedisEventSubscriber | None = None
    relay: OutboxRelay | None = None
    pool: DatabasePool | None = None
    redis_url = os.environ.get("REDIS_URL", "")
    database_config = DatabaseConfig.from_env()
    app_service = service or CorporateTravelService(publisher=InMemoryEventPublisher(), repository=InMemoryCorporateTravelRepository())
    if database_config is not None and service is None:
        pool = DatabasePool(database_config)
        migrations_dir = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "migrations")
        run_migrations(pool, migrations_dir)
        repository = PostgresCorporateTravelRepository(pool)
        app_service = CorporateTravelService(repository=repository, unit_of_work=repository.transaction)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        nonlocal subscriber, relay, pool
        if redis_url and pool is not None:
            relay = OutboxRelay(pool, redis_url=redis_url)
            relay.start()
        if redis_url:
            subscriber = RedisEventSubscriber()
            subscriber.start_in_background(
                CORPORATE_TRAVEL_SUBSCRIPTIONS,
                CORPORATE_TRAVEL_CONSUMER_GROUP,
                build_event_handler(app.state.corporate_travel_service),
                consumer_name=corporate_travel_consumer_name(),
            )
        yield
        if subscriber is not None:
            subscriber.stop()
        if relay is not None:
            relay.stop()
        if pool is not None:
            pool.close()

    app = FastAPI(title="Corporate Travel", version="0.1.0", lifespan=lifespan)
    init_opentelemetry(profile()["service_id"], app=app)
    app.state.corporate_travel_service = app_service
    set_default_service(app_service)
    configure_error_handlers(app)
    configure_runtime_endpoints(app)
    configure_corporate_travel_endpoints(app, app.state.corporate_travel_service)
    return app
