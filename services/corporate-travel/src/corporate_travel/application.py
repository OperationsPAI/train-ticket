from __future__ import annotations

import json
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Callable, Iterator, Mapping
from uuid import NAMESPACE_URL, uuid5

from train_ticket_platform.events import EventEnvelope, envelope_factory
from train_ticket_platform.ids import new_prefixed_uuid7, new_uuid7
from train_ticket_platform.messaging import EventPublisher, InMemoryEventPublisher
from train_ticket_platform.storage import OutboxAppender

from .domain import (
    AgreementPriceRef,
    AgreementStatus,
    ApprovalLevel,
    ApprovalRequest,
    AuthorizationStatus,
    AuthorizedTraveler,
    BillingCalendar,
    BillingPeriod,
    BillingPeriodStatus,
    BookingRequest,
    BudgetDimension,
    BudgetReservation,
    BudgetReservationStatus,
    BudgetPool,
    CorporateAgreement,
    CorporateTravelError,
    EffectiveWindow,
    EmployeeLevel,
    EmployeeProfile,
    InvoiceLineItem,
    Money,
    MonthlyInvoice,
    PolicyChecker,
    PolicyDecision,
    PolicyResult,
    RouteRestriction,
    SeatClass,
    SeatClassLimit,
    AdvanceBookingRule,
    BudgetLimit,
    StatementLine,
    StatementLineSource,
    TravelPolicy,
)

PRODUCER = "corporate-travel"
SCHEMA_VERSION = 1


@contextmanager
def null_unit_of_work() -> Iterator[None]:
    yield


class AgreementNotFoundError(KeyError):
    pass


class BillingPeriodNotFoundError(KeyError):
    pass


@dataclass(slots=True)
class OutboxEventRecord:
    event_id: str
    stream: str
    envelope: EventEnvelope
    published_at: datetime | None = None


@dataclass(slots=True)
class PolicyCheckResult:
    policy_result: PolicyResult
    approval_request: ApprovalRequest | None
    budget_pools: tuple[BudgetPool, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            **self.policy_result.to_dict(),
            "approvalRequest": self.approval_request.to_dict() if self.approval_request else None,
            "budgetPools": [pool.to_dict() for pool in self.budget_pools],
        }


@dataclass(slots=True)
class InvoiceResult:
    invoice: MonthlyInvoice

    def to_dict(self) -> dict[str, Any]:
        return self.invoice.to_dict()


@dataclass(slots=True)
class CorporateAgreementResult:
    agreement: CorporateAgreement

    def to_dict(self) -> dict[str, Any]:
        return self.agreement.to_dict()


@dataclass(slots=True)
class BillingPeriodResult:
    billing_period: BillingPeriod

    def to_dict(self) -> dict[str, Any]:
        return self.billing_period.to_dict()


@dataclass(slots=True)
class InMemoryCorporateTravelRepository:
    agreements: dict[str, CorporateAgreement] = field(default_factory=dict)
    billing_periods: dict[str, BillingPeriod] = field(default_factory=dict)
    processed_event_ids: set[str] = field(default_factory=set)
    open_period_by_agreement: dict[tuple[str, str], str] = field(default_factory=dict)
    policies: dict[str, TravelPolicy] = field(default_factory=dict)
    approval_requests: dict[str, ApprovalRequest] = field(default_factory=dict)
    budget_pools: dict[str, BudgetPool] = field(default_factory=dict)
    outbox: list[OutboxEventRecord] = field(default_factory=list)
    invoice_lines: dict[tuple[str, str], list[InvoiceLineItem]] = field(default_factory=dict)

    def enqueue_outbox(self, envelope: EventEnvelope) -> None:
        if any(record.event_id == envelope.eventId for record in self.outbox):
            return
        self.outbox.append(OutboxEventRecord(envelope.eventId, f"events:{envelope.producer}", envelope))

    def save_policy(self, policy: TravelPolicy) -> None:
        self.policies[policy.agreement_id] = policy

    def get_policy(self, agreement_id: str) -> TravelPolicy:
        return self.policies.get(agreement_id) or TravelPolicy.default(agreement_id)

    def save_approval_request(self, request: ApprovalRequest) -> None:
        self.approval_requests[request.request_id] = request

    def save_budget_pool(self, pool: BudgetPool) -> None:
        self.budget_pools[pool.pool_id] = pool

    def save_agreement(self, agreement: CorporateAgreement) -> None:
        self.agreements[agreement.agreement_id] = agreement

    def get_agreement(self, agreement_id: str) -> CorporateAgreement:
        try:
            return self.agreements[agreement_id]
        except KeyError as exc:
            raise AgreementNotFoundError(agreement_id) from exc

    def save_billing_period(self, period: BillingPeriod) -> None:
        self.billing_periods[period.statement_id] = period
        self.open_period_by_agreement[(period.agreement_id, period.billing_period)] = period.statement_id

    def get_billing_period(self, statement_id: str) -> BillingPeriod:
        try:
            return self.billing_periods[statement_id]
        except KeyError as exc:
            raise BillingPeriodNotFoundError(statement_id) from exc

    def find_open_period(self, agreement_id: str, billing_period: str) -> BillingPeriod | None:
        statement_id = self.open_period_by_agreement.get((agreement_id, billing_period))
        return self.billing_periods.get(statement_id) if statement_id else None

    def try_mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        del stream
        if event_id in self.processed_event_ids:
            return False
        self.processed_event_ids.add(event_id)
        return True

    def pending_outbox(self) -> tuple[OutboxEventRecord, ...]:
        return tuple(record for record in self.outbox if record.published_at is None)


class _TxState:
    def __init__(self, connection: Any) -> None:
        self.connection = connection


_CORPORATE_TRAVEL_TX: ContextVar[_TxState | None] = ContextVar("corporate_travel_postgres_tx", default=None)


def _jsonb_payload(value: Mapping[str, Any]) -> Any:
    try:
        from psycopg.types.json import Jsonb

        return Jsonb(dict(value))
    except ImportError:  # pragma: no cover - psycopg optional in unit tests
        return json.dumps(dict(value), separators=(",", ":"))


def _event_stream(envelope: EventEnvelope) -> str:
    return f"events:{envelope.producer}"


class PostgresCorporateTravelRepository:
    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()

    @contextmanager
    def transaction(self) -> Iterator[None]:
        state = _CORPORATE_TRAVEL_TX.get()
        if state is not None:
            yield
            return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _CORPORATE_TRAVEL_TX.set(_TxState(conn))
                try:
                    yield
                finally:
                    _CORPORATE_TRAVEL_TX.reset(token)

    def with_connection(self, fn: Callable[[Any], Any]) -> Any:
        state = _CORPORATE_TRAVEL_TX.get()
        if state is not None:
            return fn(state.connection)
        with self.transaction():
            state = _CORPORATE_TRAVEL_TX.get()
            if state is None:  # pragma: no cover - defensive
                raise RuntimeError("corporate travel transaction was not established")
            return fn(state.connection)

    def enqueue_outbox(self, envelope: EventEnvelope) -> None:
        self.with_connection(lambda conn: self._outbox.append(conn, envelope, stream=_event_stream(envelope)))

    def pending_outbox(self) -> tuple[OutboxEventRecord, ...]:
        def read(conn: Any) -> tuple[OutboxEventRecord, ...]:
            rows = conn.execute("SELECT event_id, stream, envelope, published_at FROM outbox WHERE published_at IS NULL ORDER BY seq").fetchall()
            return tuple(OutboxEventRecord(str(row[0]), str(row[1]), EventEnvelope.from_json_dict(dict(row[2])), row[3]) for row in rows)

        return self.with_connection(read)

    def save_policy(self, policy: TravelPolicy) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO travel_policies(agreement_id, document) VALUES (%s, %s) "
                "ON CONFLICT (agreement_id) DO UPDATE SET document = EXCLUDED.document, updated_at = now()",
                (policy.agreement_id, _jsonb_payload(policy.to_dict())),
            )
        )

    def get_policy(self, agreement_id: str) -> TravelPolicy:
        row = self.with_connection(lambda conn: conn.execute("SELECT document FROM travel_policies WHERE agreement_id = %s", (agreement_id,)).fetchone())
        return _policy_from_dict(dict(row[0])) if row else TravelPolicy.default(agreement_id)

    def save_approval_request(self, request: ApprovalRequest) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO approval_requests(request_id, booking_ref, employee_ref, current_level, status, document) "
                "VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT (request_id) DO UPDATE SET "
                "booking_ref = EXCLUDED.booking_ref, employee_ref = EXCLUDED.employee_ref, current_level = EXCLUDED.current_level, "
                "status = EXCLUDED.status, document = EXCLUDED.document, updated_at = now()",
                (request.request_id, request.booking_ref, request.employee_ref, request.current_level.value, request.status.value, _jsonb_payload(request.to_dict())),
            )
        )

    def save_budget_pool(self, pool: BudgetPool) -> None:
        def write(conn: Any) -> None:
            conn.execute(
                "INSERT INTO budget_pools(pool_id, dimension, period_start, period_end, limit_minor, reserved_minor, committed_minor, document) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (pool_id) DO UPDATE SET "
                "dimension = EXCLUDED.dimension, period_start = EXCLUDED.period_start, period_end = EXCLUDED.period_end, "
                "limit_minor = EXCLUDED.limit_minor, reserved_minor = EXCLUDED.reserved_minor, committed_minor = EXCLUDED.committed_minor, "
                "document = EXCLUDED.document, updated_at = now()",
                (pool.pool_id, pool.dimension.value, pool.period_start, pool.period_end, pool.limit_minor, pool.reserved_minor, pool.committed_minor, _jsonb_payload(pool.to_dict())),
            )
            for reservation in pool.reservations:
                conn.execute(
                    "INSERT INTO budget_reservations(reservation_id, pool_id, booking_ref, amount_minor, status) VALUES (%s, %s, %s, %s, %s) "
                    "ON CONFLICT (reservation_id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()",
                    (reservation.reservation_id, reservation.pool_id, reservation.booking_ref, reservation.amount_minor, reservation.status.value),
                )

        self.with_connection(write)

    @property
    def budget_pools(self) -> dict[str, BudgetPool]:
        rows = self.with_connection(lambda conn: conn.execute("SELECT pool_id, document FROM budget_pools").fetchall())
        return {str(row[0]): _budget_pool_from_dict(dict(row[1])) for row in rows}

    def save_agreement(self, agreement: CorporateAgreement) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO corporate_agreements(agreement_id, corporate_id, agreement_code, legal_name, agreement_version, status, "
                "effective_starts_at, effective_ends_at, monthly_credit_currency, monthly_credit_minor_units, agreement_price_ref_digest, document, activated_at) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (agreement_id) DO UPDATE SET "
                "corporate_id = EXCLUDED.corporate_id, agreement_code = EXCLUDED.agreement_code, legal_name = EXCLUDED.legal_name, "
                "agreement_version = EXCLUDED.agreement_version, status = EXCLUDED.status, effective_starts_at = EXCLUDED.effective_starts_at, "
                "effective_ends_at = EXCLUDED.effective_ends_at, monthly_credit_currency = EXCLUDED.monthly_credit_currency, "
                "monthly_credit_minor_units = EXCLUDED.monthly_credit_minor_units, agreement_price_ref_digest = EXCLUDED.agreement_price_ref_digest, "
                "document = EXCLUDED.document, activated_at = EXCLUDED.activated_at",
                (
                    agreement.agreement_id,
                    agreement.corporate_id,
                    agreement.agreement_code,
                    agreement.legal_name,
                    agreement.version,
                    agreement.status.value,
                    agreement.effective_window.starts_at,
                    agreement.effective_window.ends_at,
                    agreement.monthly_credit_limit.currency,
                    agreement.monthly_credit_limit.minor_units,
                    agreement.price_ref.digest,
                    _jsonb_payload(agreement.to_dict()),
                    agreement.activated_at,
                ),
            )
        )

    def get_agreement(self, agreement_id: str) -> CorporateAgreement:
        row = self.with_connection(lambda conn: conn.execute("SELECT document FROM corporate_agreements WHERE agreement_id = %s", (agreement_id,)).fetchone())
        if row is None:
            raise AgreementNotFoundError(agreement_id)
        return _agreement_from_dict(dict(row[0]))

    def save_billing_period(self, period: BillingPeriod) -> None:
        self.with_connection(
            lambda conn: conn.execute(
                "INSERT INTO corporate_billing_periods(statement_id, corporate_id, agreement_id, billing_period, currency, status, statement_hash, cutoff_at, due_at, closed_at, document) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (corporate_id, agreement_id, billing_period) DO UPDATE SET "
                "status = EXCLUDED.status, statement_hash = EXCLUDED.statement_hash, closed_at = EXCLUDED.closed_at, document = EXCLUDED.document",
                (period.statement_id, period.corporate_id, period.agreement_id, period.billing_period, period.currency, period.status.value, period.statement_hash, period.cutoff_at, period.due_at, period.closed_at, _jsonb_payload(period.to_dict())),
            )
        )

    def get_billing_period(self, statement_id: str) -> BillingPeriod:
        row = self.with_connection(lambda conn: conn.execute("SELECT document FROM corporate_billing_periods WHERE statement_id = %s", (statement_id,)).fetchone())
        if row is None:
            raise BillingPeriodNotFoundError(statement_id)
        return _billing_period_from_dict(dict(row[0]))

    def find_open_period(self, agreement_id: str, billing_period: str) -> BillingPeriod | None:
        row = self.with_connection(
            lambda conn: conn.execute(
                "SELECT document FROM corporate_billing_periods WHERE agreement_id = %s AND billing_period = %s AND status = 'OPEN'",
                (agreement_id, billing_period),
            ).fetchone()
        )
        return _billing_period_from_dict(dict(row[0])) if row else None

    def try_mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        return bool(
            self.with_connection(
                lambda conn: conn.execute(
                    "INSERT INTO processed_events(event_id, stream) VALUES (%s, %s) ON CONFLICT DO NOTHING RETURNING event_id",
                    (event_id, stream),
                ).fetchone()
            )
        )


def _agreement_from_dict(data: Mapping[str, Any]) -> CorporateAgreement:
    return CorporateAgreement(
        agreement_id=str(data["agreementId"]),
        corporate_id=str(data["corporateId"]),
        agreement_code=str(data["agreementCode"]),
        legal_name=str(data["legalName"]),
        version=int(data["agreementVersion"]),
        effective_window=EffectiveWindow(parse_rfc3339(str(data["effectiveWindow"]["startsAt"])), parse_rfc3339(str(data["effectiveWindow"]["endsAt"]))),
        price_ref=AgreementPriceRef(tuple(str(ref) for ref in data["priceRef"].get("fareRuleRefs", ())), _optional_text(data["priceRef"].get("ruleSetId")), _optional_text(data["priceRef"].get("ruleSetVersion"))),
        monthly_credit_limit=money_from_mapping(data["monthlyCreditLimit"]),
        billing_calendar=BillingCalendar(str(data["billingCalendar"]["billingPeriod"]), parse_rfc3339(str(data["billingCalendar"]["cutoffAt"])), parse_rfc3339(str(data["billingCalendar"]["dueAt"])), str(data["billingCalendar"].get("timezonePolicy", "UTC"))),
        contact=dict(data.get("contact") or {}),
        status=AgreementStatus(str(data["status"])),
        authorized_travelers=tuple(_traveler_from_dict(item) for item in data.get("authorizedTravelers", ())),
        created_at=parse_rfc3339(str(data["createdAt"])),
        activated_at=parse_rfc3339(str(data["activatedAt"])) if data.get("activatedAt") else None,
    )


def _traveler_from_dict(data: Mapping[str, Any]) -> AuthorizedTraveler:
    return AuthorizedTraveler(
        authorization_id=str(data["authorizationId"]),
        account_id=str(data["accountId"]),
        corporate_id=str(data["corporateId"]),
        agreement_id=str(data["agreementId"]),
        cost_center=str(data["costCenter"]),
        project_code=_optional_text(data.get("projectCode")),
        scope=dict(data.get("scope") or {}),
        max_trip_amount=money_from_mapping(data["maxTripAmount"]) if data.get("maxTripAmount") else None,
        can_delegate_booking=bool(data.get("canDelegateBooking", False)),
        valid_from=parse_rfc3339(str(data["validFrom"])),
        valid_until=parse_rfc3339(str(data["validUntil"])),
        status=AuthorizationStatus(str(data["status"])),
        version=int(data.get("version", 1)),
    )


def _billing_period_from_dict(data: Mapping[str, Any]) -> BillingPeriod:
    return BillingPeriod(
        statement_id=str(data["statementId"]),
        corporate_id=str(data["corporateId"]),
        agreement_id=str(data["agreementId"]),
        billing_period=str(data["billingPeriod"]),
        currency=str(data["currency"]),
        cutoff_at=parse_rfc3339(str(data["cutoffAt"])),
        due_at=parse_rfc3339(str(data["dueAt"])),
        status=BillingPeriodStatus(str(data["status"])),
        lines=tuple(_statement_line_from_dict(item) for item in data.get("lines", ())),
        statement_hash=_optional_text(data.get("statementHash")),
        closed_at=parse_rfc3339(str(data["closedAt"])) if data.get("closedAt") else None,
    )


def _statement_line_from_dict(data: Mapping[str, Any]) -> StatementLine:
    return StatementLine(
        line_id=str(data["lineId"]),
        source_type=StatementLineSource(str(data["sourceType"])),
        source_business_ref=str(data["sourceBusinessRef"]),
        order_id=_optional_text(data.get("orderId")),
        payment_intent_id=_optional_text(data.get("paymentIntentId")),
        amount=money_from_mapping(data["amount"]),
        authorization_snapshot_ref=_optional_text(data.get("authorizationSnapshotRef")),
        source_event_id=str(data["sourceEventId"]),
        occurred_at=parse_rfc3339(str(data["occurredAt"])),
    )


def _budget_pool_from_dict(data: Mapping[str, Any]) -> BudgetPool:
    return BudgetPool(
        pool_id=str(data["poolId"]),
        dimension=BudgetDimension(str(data["dimension"])),
        period_start=parse_rfc3339(str(data["periodStart"])),
        period_end=parse_rfc3339(str(data["periodEnd"])),
        limit_minor=int(data["limitMinor"]),
        reserved_minor=int(data["reservedMinor"]),
        committed_minor=int(data["committedMinor"]),
        reservations=tuple(_budget_reservation_from_dict(item) for item in data.get("reservations", ())),
    )


def _budget_reservation_from_dict(data: Mapping[str, Any]) -> BudgetReservation:
    return BudgetReservation(str(data["reservationId"]), str(data["poolId"]), str(data["bookingRef"]), int(data["amountMinor"]), BudgetReservationStatus(str(data["status"])))


def _policy_from_dict(data: Mapping[str, Any]) -> TravelPolicy:
    rules = []
    for rule in data.get("rules", ()):
        rule_type = rule.get("type")
        if rule_type == "SEAT_CLASS_LIMIT":
            rules.append(SeatClassLimit(SeatClass(str(rule.get("default", "SECOND_CLASS"))), bool(rule.get("vpException", True)), int(rule.get("longTripMinutes", 360))))
        elif rule_type == "ADVANCE_BOOKING":
            rules.append(AdvanceBookingRule(int(rule.get("minimumDays", 3)), bool(rule.get("emergencyRequiresApproval", True))))
        elif rule_type == "ROUTE_RESTRICTION":
            routes = tuple((str(item[0]), str(item[1])) for item in rule.get("allowedRoutes", ()))
            rules.append(RouteRestriction(routes, bool(rule.get("requireApprovalForUnlisted", True))))
        elif rule_type == "BUDGET_LIMIT":
            rules.append(BudgetLimit(int(rule.get("perTripMinor", 200000)), int(rule.get("perEmployeeMonthlyMinor", 800000)), int(rule.get("perDepartmentMonthlyMinor", 10000000))))
    return TravelPolicy(str(data["agreementId"]), tuple(rules) or TravelPolicy.default(str(data["agreementId"])).rules)


@dataclass(slots=True)
class CorporateTravelService:
    publisher: EventPublisher = field(default_factory=InMemoryEventPublisher)
    repository: InMemoryCorporateTravelRepository = field(default_factory=InMemoryCorporateTravelRepository)
    unit_of_work: Callable[[], Iterator[None]] = null_unit_of_work

    def create_agreement(
        self,
        *,
        corporate_id: str,
        agreement_code: str,
        legal_name: str,
        effective_window: Mapping[str, str],
        price_ref: Mapping[str, Any],
        monthly_credit_limit: Mapping[str, Any],
        billing_calendar: Mapping[str, str],
        contact: Mapping[str, str] | None = None,
        activate: bool = True,
        correlation_id: str | None = None,
        causation_id: str | None = None,
    ) -> CorporateAgreementResult:
        with self.unit_of_work():
            agreement_id = prefixed_id("agr")
            agreement = CorporateAgreement(
                agreement_id=agreement_id,
                corporate_id=corporate_id,
                agreement_code=agreement_code,
                legal_name=legal_name,
                version=1,
                effective_window=EffectiveWindow(
                    starts_at=parse_rfc3339(str(effective_window["startsAt"])),
                    ends_at=parse_rfc3339(str(effective_window["endsAt"])),
                ),
                price_ref=AgreementPriceRef(
                    fare_rule_refs=tuple(str(ref) for ref in price_ref.get("fareRuleRefs", ())),
                    rule_set_id=_optional_text(price_ref.get("ruleSetId")),
                    rule_set_version=_optional_text(price_ref.get("ruleSetVersion")),
                ),
                monthly_credit_limit=money_from_mapping(monthly_credit_limit),
                billing_calendar=BillingCalendar(
                    period=str(billing_calendar["billingPeriod"]),
                    cutoff_at=parse_rfc3339(str(billing_calendar["cutoffAt"])),
                    due_at=parse_rfc3339(str(billing_calendar["dueAt"])),
                    timezone_policy=str(billing_calendar.get("timezonePolicy", "UTC")),
                ),
                contact=dict(contact or {}),
            )
            created_event = _agreement_event("CorporateAgreementCreated", agreement, correlation_id, causation_id)
            if activate:
                agreement = agreement.activate()
                activated_event = _agreement_event("CorporateAgreementActivated", agreement, correlation_id, created_event.eventId)
                self.repository.save_agreement(agreement)
                self._publish(created_event)
                self._publish(activated_event)
                return CorporateAgreementResult(agreement)
            self.repository.save_agreement(agreement)
            self._publish(created_event)
            return CorporateAgreementResult(agreement)

    def get_agreement(self, agreement_id: str) -> CorporateAgreementResult:
        return CorporateAgreementResult(self.repository.get_agreement(agreement_id))

    def authorize_traveler(
        self,
        *,
        agreement_id: str,
        account_id: str,
        cost_center: str,
        project_code: str | None = None,
        scope: Mapping[str, Any] | None = None,
        max_trip_amount: Mapping[str, Any] | None = None,
        can_delegate_booking: bool = False,
        valid_from: str | None = None,
        valid_until: str | None = None,
        correlation_id: str | None = None,
        causation_id: str | None = None,
    ) -> AuthorizedTraveler:
        with self.unit_of_work():
            agreement = self.repository.get_agreement(agreement_id)
            traveler = AuthorizedTraveler(
                authorization_id=prefixed_id("auth"),
                account_id=account_id,
                corporate_id=agreement.corporate_id,
                agreement_id=agreement.agreement_id,
                cost_center=cost_center,
                project_code=project_code,
                scope=dict(scope or {}),
                max_trip_amount=money_from_mapping(max_trip_amount) if max_trip_amount else None,
                can_delegate_booking=can_delegate_booking,
                valid_from=parse_rfc3339(valid_from) if valid_from else agreement.effective_window.starts_at,
                valid_until=parse_rfc3339(valid_until) if valid_until else agreement.effective_window.ends_at,
            )
            updated = agreement.authorize_traveler(traveler)
            self.repository.save_agreement(updated)
            self._publish(_authorization_event(traveler, correlation_id, causation_id))
            return traveler

    def handle_event(self, envelope: EventEnvelope) -> None:
        with self.unit_of_work():
            if envelope.eventType not in {"JourneyOrderConfirmed", "PaymentCaptured", "PostSalesRefundCompleted", "TripCancelled"}:
                return
            if not self.repository.try_mark_processed(envelope.eventId, f"events:{envelope.producer}"):
                return
            if envelope.eventType in {"PostSalesRefundCompleted", "TripCancelled"}:
                booking_ref = str(envelope.payload.get("bookingRef") or envelope.payload.get("orderId") or "")
                self.release_budget_reservations(booking_ref=booking_ref, correlation_id=envelope.correlationId, causation_id=envelope.eventId)
                return
            line = line_from_event(envelope)
            agreement = self.repository.get_agreement(str(envelope.payload["agreementId"]))
            booking_ref = str(envelope.payload.get("bookingRef") or envelope.payload.get("orderId") or "")
            if envelope.eventType == "PaymentCaptured" and booking_ref:
                self.commit_budget_reservations(booking_ref=booking_ref, correlation_id=envelope.correlationId, causation_id=envelope.eventId)
            billing_period = str(envelope.payload.get("billingPeriod") or agreement.billing_calendar.period)
            period = self.repository.find_open_period(agreement.agreement_id, billing_period)
            if period is None:
                period = BillingPeriod(
                    statement_id=deterministic_prefixed_id("stmt", agreement.agreement_id, billing_period),
                    corporate_id=agreement.corporate_id,
                    agreement_id=agreement.agreement_id,
                    billing_period=billing_period,
                    currency=agreement.monthly_credit_limit.currency,
                    cutoff_at=agreement.billing_calendar.cutoff_at,
                    due_at=agreement.billing_calendar.due_at,
                )
            self.repository.save_billing_period(period.attach_line(line))

    def check_policy_and_reserve(
        self,
        *,
        agreement_id: str,
        employee_ref: str,
        department_ref: str,
        origin: str,
        destination: str,
        seat_class: str,
        amount: Mapping[str, Any],
        requested_at: str,
        departure_at: str,
        trip_duration_minutes: int,
        employee_level: str = "STAFF",
        manager_ref: str | None = None,
        emergency: bool = False,
        booking_ref: str | None = None,
        correlation_id: str | None = None,
        causation_id: str | None = None,
    ) -> PolicyCheckResult:
        with self.unit_of_work():
            agreement = self.repository.get_agreement(agreement_id)
            money = money_from_mapping(amount)
            if money.currency != agreement.monthly_credit_limit.currency:
                raise CorporateTravelError("booking amount currency must match agreement currency")
            booking = BookingRequest(
                booking_ref=booking_ref or prefixed_id("book"),
                agreement_id=agreement_id,
                employee_ref=employee_ref,
                department_ref=department_ref,
                origin=origin,
                destination=destination,
                seat_class=SeatClass(seat_class),
                amount=money,
                requested_at=parse_rfc3339(requested_at),
                departure_at=parse_rfc3339(departure_at),
                trip_duration_minutes=trip_duration_minutes,
                emergency=emergency,
            )
            employee = EmployeeProfile(employee_ref=employee_ref, department_ref=department_ref, level=EmployeeLevel(employee_level), manager_ref=manager_ref)
            month_start = datetime(booking.departure_at.year, booking.departure_at.month, 1, tzinfo=UTC)
            month_end = _next_month(month_start)
            employee_pool = self._budget_pool(BudgetDimension.EMPLOYEE, agreement_id, employee_ref, month_start, month_end, 800_000)
            department_pool = self._budget_pool(BudgetDimension.DEPARTMENT, agreement_id, department_ref, month_start, month_end, 10_000_000)
            quarter_start = _quarter_start(booking.departure_at)
            agreement_pool = self._budget_pool(
                BudgetDimension.AGREEMENT,
                agreement_id,
                agreement_id,
                quarter_start,
                _add_months(quarter_start, 3),
                agreement.monthly_credit_limit.minor_units * 3,
            )
            context = {
                "employeeMonthlyUsedMinor": employee_pool.utilized_minor,
                "departmentMonthlyUsedMinor": department_pool.utilized_minor,
            }
            result = PolicyChecker().check(booking, employee, self.repository.get_policy(agreement_id), context)
            self._publish(_policy_checked_event(booking, result, correlation_id, causation_id))
            approval_request: ApprovalRequest | None = None
            if result.decision is PolicyDecision.COMPLIANT:
                approval_request = ApprovalRequest.for_policy_result(prefixed_id("appr"), booking.booking_ref, employee_ref, result)
                self.repository.save_approval_request(approval_request)
                self._publish(_approval_event("ApprovalGranted", approval_request, correlation_id, causation_id))
                pools = self._reserve_budget(booking, (employee_pool, department_pool, agreement_pool), allow_over_limit=False, correlation_id=correlation_id, causation_id=causation_id)
            elif result.requires_level is ApprovalLevel.FINANCE:
                approval_request = ApprovalRequest.for_policy_result(prefixed_id("appr"), booking.booking_ref, employee_ref, result)
                self.repository.save_approval_request(approval_request)
                self._publish(_approval_event("ApprovalRequested", approval_request, correlation_id, causation_id))
                pools = (employee_pool, department_pool, agreement_pool)
            else:
                approval_request = ApprovalRequest.for_policy_result(prefixed_id("appr"), booking.booking_ref, employee_ref, result)
                self.repository.save_approval_request(approval_request)
                self._publish(_approval_event("ApprovalRequested", approval_request, correlation_id, causation_id))
                pools = self._reserve_budget(booking, (employee_pool, department_pool, agreement_pool), allow_over_limit=True, correlation_id=correlation_id, causation_id=causation_id)
            return PolicyCheckResult(result, approval_request, pools)

    def commit_budget_reservations(self, *, booking_ref: str, correlation_id: str | None = None, causation_id: str | None = None) -> tuple[BudgetPool, ...]:
        with self.unit_of_work():
            updated = []
            for pool in tuple(self.repository.budget_pools.values()):
                committed = pool.commit(booking_ref)
                if committed is not pool:
                    self.repository.save_budget_pool(committed)
                    updated.append(committed)
            return tuple(updated)

    def release_budget_reservations(self, *, booking_ref: str, correlation_id: str | None = None, causation_id: str | None = None) -> tuple[BudgetPool, ...]:
        with self.unit_of_work():
            updated = []
            for pool in tuple(self.repository.budget_pools.values()):
                released = pool.release(booking_ref)
                if released is not pool:
                    self.repository.save_budget_pool(released)
                    updated.append(released)
            if updated:
                self._publish(_budget_released_event(booking_ref, updated, correlation_id, causation_id))
            return tuple(updated)

    def generate_monthly_invoice(
        self,
        *,
        agreement_id: str,
        period: str,
        line_items: list[Mapping[str, Any]],
        discount_rate_bps: int = 0,
        correlation_id: str | None = None,
        causation_id: str | None = None,
    ) -> InvoiceResult:
        with self.unit_of_work():
            self.repository.get_agreement(agreement_id)
            items = tuple(
                InvoiceLineItem(
                    booking_ref=str(item["bookingRef"]),
                    employee_name=str(item["employeeName"]),
                    route=str(item["route"]),
                    travel_date=parse_rfc3339(item["travelDate"]),
                    amount_minor=int(item["amountMinor"]),
                )
                for item in line_items
            )
            invoice = MonthlyInvoice.generate(prefixed_id("inv"), agreement_id, period, items, discount_rate_bps)
            self._publish(_invoice_event(invoice, correlation_id, causation_id))
            return InvoiceResult(invoice)

    def _budget_pool(self, dimension: BudgetDimension, agreement_id: str, subject_ref: str, period_start: datetime, period_end: datetime, limit_minor: int) -> BudgetPool:
        pool_id = deterministic_prefixed_id("budget", agreement_id, dimension.value, subject_ref, period_start.strftime("%Y-%m"))
        pool = self.repository.budget_pools.get(pool_id)
        if pool:
            return pool
        pool = BudgetPool(pool_id, dimension, period_start, period_end, limit_minor)
        self.repository.save_budget_pool(pool)
        return pool

    def _reserve_budget(self, booking: BookingRequest, pools: tuple[BudgetPool, ...], *, allow_over_limit: bool, correlation_id: str | None, causation_id: str | None) -> tuple[BudgetPool, ...]:
        updated = []
        for pool in pools:
            reserved = pool.reserve(deterministic_prefixed_id("resv", pool.pool_id, booking.booking_ref), booking.booking_ref, booking.amount.minor_units, allow_over_limit=allow_over_limit)
            self.repository.save_budget_pool(reserved)
            updated.append(reserved)
            threshold = reserved.alert_threshold()
            if threshold is not None:
                self._publish(_budget_alert_event(reserved, threshold, correlation_id, causation_id))
        return tuple(updated)

    def _publish(self, envelope: EventEnvelope) -> None:
        self.repository.enqueue_outbox(envelope)

    def flush_outbox(self) -> None:
        """Relay unpublished in-memory outbox rows for local tests/dev only.

        Production deployments use the platform OutboxRelay against the durable
        outbox table. The application service only enqueues events as part of
        the state-changing unit of work.
        """
        for record in self.repository.pending_outbox():
            self.publisher.publish(record.envelope)
            record.published_at = datetime.now(UTC)

    def append_outbox(self, conn: Any, envelope: EventEnvelope) -> None:
        OutboxAppender().append(conn, envelope)

    def close_billing_period(
        self,
        *,
        agreement_id: str,
        billing_period: str,
        correlation_id: str | None = None,
        causation_id: str | None = None,
    ) -> BillingPeriodResult:
        with self.unit_of_work():
            agreement = self.repository.get_agreement(agreement_id)
            period = self.repository.find_open_period(agreement_id, billing_period)
            if period is None:
                period = BillingPeriod(
                    statement_id=deterministic_prefixed_id("stmt", agreement_id, billing_period),
                    corporate_id=agreement.corporate_id,
                    agreement_id=agreement_id,
                    billing_period=billing_period,
                    currency=agreement.monthly_credit_limit.currency,
                    cutoff_at=agreement.billing_calendar.cutoff_at,
                    due_at=agreement.billing_calendar.due_at,
                )
            closed = period.freeze().close()
            self.repository.save_billing_period(closed)
            self._publish(_billing_event(closed, correlation_id, causation_id))
            return BillingPeriodResult(closed)


def line_from_event(envelope: EventEnvelope) -> StatementLine:
    payload = envelope.payload
    amount = money_from_mapping(payload.get("amount") or payload.get("capturedAmount") or {})
    order_id = _optional_text(payload.get("orderId"))
    payment_id = _optional_text(payload.get("paymentIntentId"))
    source = StatementLineSource.JOURNEY_ORDER_CONFIRMED if envelope.eventType == "JourneyOrderConfirmed" else StatementLineSource.PAYMENT_CAPTURED
    source_ref = payment_id or order_id or envelope.eventId
    return StatementLine(
        line_id=deterministic_prefixed_id("line", envelope.eventId),
        source_type=source,
        source_business_ref=source_ref,
        order_id=order_id,
        payment_intent_id=payment_id,
        amount=amount,
        authorization_snapshot_ref=_optional_text(payload.get("authorizationSnapshotRef")),
        source_event_id=envelope.eventId,
        occurred_at=parse_rfc3339(envelope.occurredAt) if isinstance(envelope.occurredAt, str) else envelope.occurredAt,
    )


def money_from_mapping(value: Mapping[str, Any]) -> Money:
    return Money(currency=str(value["currency"]), minor_units=int(value["minorUnits"]))


def parse_rfc3339(value: str | datetime) -> datetime:
    if isinstance(value, datetime):
        return (value if value.tzinfo is not None else value.replace(tzinfo=UTC)).astimezone(UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def prefixed_id(prefix: str) -> str:
    return new_prefixed_uuid7(prefix)


def uuid7() -> str:
    return new_uuid7()


def deterministic_prefixed_id(prefix: str, *parts: str) -> str:
    uuid = uuid5(NAMESPACE_URL, f"train-ticket:corporate-travel:{':'.join(parts)}")
    uuid_int = (uuid.int & ~(0xF << 76)) | (0x7 << 76)
    return f"{prefix}-{uuid.__class__(int=uuid_int)}"


def _agreement_event(event_type: str, agreement: CorporateAgreement, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    payload = agreement.to_dict()
    return envelope_factory(
        event_type=event_type,
        producer=PRODUCER,
        payload=payload,
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=agreement.activated_at if event_type == "CorporateAgreementActivated" else agreement.created_at,
        schema_version=SCHEMA_VERSION,
    )


def _authorization_event(traveler: AuthorizedTraveler, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type="EmployeeAuthorizationGranted",
        producer=PRODUCER,
        payload=traveler.to_dict(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        schema_version=SCHEMA_VERSION,
    )


def _billing_event(period: BillingPeriod, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type="CorporateBillingPeriodClosed",
        producer=PRODUCER,
        payload=period.to_dict(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        occurred_at=period.closed_at,
        schema_version=SCHEMA_VERSION,
    )


def _policy_checked_event(booking: BookingRequest, result: PolicyResult, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type="TravelPolicyChecked",
        producer=PRODUCER,
        payload={"booking": booking.to_dict(), **result.to_dict()},
        correlation_id=correlation_id,
        causation_id=causation_id,
        schema_version=SCHEMA_VERSION,
    )


def _approval_event(event_type: str, request: ApprovalRequest, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type=event_type,
        producer=PRODUCER,
        payload=request.to_dict(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        schema_version=SCHEMA_VERSION,
    )


def _budget_alert_event(pool: BudgetPool, threshold: int, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type="BudgetAlertTriggered",
        producer=PRODUCER,
        payload={"thresholdPercent": threshold, "pool": pool.to_dict()},
        correlation_id=correlation_id,
        causation_id=causation_id,
        schema_version=SCHEMA_VERSION,
    )


def _budget_released_event(booking_ref: str, pools: tuple[BudgetPool, ...], correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type="BudgetReservationReleased",
        producer=PRODUCER,
        payload={"bookingRef": booking_ref, "budgetPools": [pool.to_dict() for pool in pools]},
        correlation_id=correlation_id,
        causation_id=causation_id,
        schema_version=SCHEMA_VERSION,
    )


def _invoice_event(invoice: MonthlyInvoice, correlation_id: str | None, causation_id: str | None) -> EventEnvelope:
    return envelope_factory(
        event_type="ConsolidatedInvoiceGenerated",
        producer=PRODUCER,
        payload=invoice.to_dict(),
        correlation_id=correlation_id,
        causation_id=causation_id,
        schema_version=SCHEMA_VERSION,
    )


def _next_month(value: datetime) -> datetime:
    return _add_months(value, 1)


def _quarter_start(value: datetime) -> datetime:
    month = ((value.month - 1) // 3) * 3 + 1
    return datetime(value.year, month, 1, tzinfo=UTC)


def _add_months(value: datetime, months: int) -> datetime:
    month_index = value.month - 1 + months
    year = value.year + month_index // 12
    month = month_index % 12 + 1
    return datetime(year, month, 1, tzinfo=UTC)


def _optional_text(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
