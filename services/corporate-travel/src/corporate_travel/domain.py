from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta
from enum import Enum
from hashlib import sha256
from typing import Any, Mapping, Self


class CorporateTravelError(ValueError):
    """Raised when corporate-travel invariants are violated."""


class AgreementStatus(str, Enum):
    DRAFT = "DRAFT"
    ACTIVE = "ACTIVE"
    SUSPENDED = "SUSPENDED"
    EXPIRED = "EXPIRED"
    REJECTED = "REJECTED"
    FAILED = "FAILED"
    TERMINATED = "TERMINATED"


class AuthorizationStatus(str, Enum):
    GRANTED = "GRANTED"
    ACTIVE = "ACTIVE"
    SUSPENDED = "SUSPENDED"
    REVOKED = "REVOKED"
    EXPIRED = "EXPIRED"
    FAILED = "FAILED"


class BillingPeriodStatus(str, Enum):
    OPEN = "OPEN"
    FROZEN = "FROZEN"
    SUBMITTED = "SUBMITTED"
    ACCEPTED = "ACCEPTED"
    CLOSED = "CLOSED"
    MISSED = "MISSED"
    FAILED = "FAILED"


class StatementLineSource(str, Enum):
    JOURNEY_ORDER_CONFIRMED = "JOURNEY_ORDER_CONFIRMED"
    PAYMENT_CAPTURED = "PAYMENT_CAPTURED"
    ADJUSTMENT = "ADJUSTMENT"


@dataclass(frozen=True, slots=True)
class Money:
    currency: str
    minor_units: int

    def __post_init__(self) -> None:
        currency = self.currency.strip().upper()
        if len(currency) != 3 or not currency.isalpha():
            raise CorporateTravelError("currency must be an ISO-4217 code")
        if self.minor_units < 0:
            raise CorporateTravelError("minor_units must be non-negative")
        object.__setattr__(self, "currency", currency)

    def to_dict(self) -> dict[str, Any]:
        return {"currency": self.currency, "minorUnits": self.minor_units}


@dataclass(frozen=True, slots=True)
class EffectiveWindow:
    starts_at: datetime
    ends_at: datetime

    def __post_init__(self) -> None:
        starts = _coerce_utc(self.starts_at)
        ends = _coerce_utc(self.ends_at)
        if ends <= starts:
            raise CorporateTravelError("effective window end must be after start")
        object.__setattr__(self, "starts_at", starts)
        object.__setattr__(self, "ends_at", ends)

    def contains(self, value: datetime) -> bool:
        at = _coerce_utc(value)
        return self.starts_at <= at < self.ends_at

    def to_dict(self) -> dict[str, str]:
        return {"startsAt": rfc3339_utc(self.starts_at), "endsAt": rfc3339_utc(self.ends_at)}


@dataclass(frozen=True, slots=True)
class BillingCalendar:
    period: str
    cutoff_at: datetime
    due_at: datetime
    timezone_policy: str = "UTC"

    def __post_init__(self) -> None:
        if not self.period.strip():
            raise CorporateTravelError("billing period is required")
        cutoff = _coerce_utc(self.cutoff_at)
        due = _coerce_utc(self.due_at)
        if due <= cutoff:
            raise CorporateTravelError("billing due_at must be after cutoff_at")
        if not self.timezone_policy.strip():
            raise CorporateTravelError("timezone_policy is required")
        object.__setattr__(self, "cutoff_at", cutoff)
        object.__setattr__(self, "due_at", due)

    def to_dict(self) -> dict[str, str]:
        return {
            "billingPeriod": self.period,
            "cutoffAt": rfc3339_utc(self.cutoff_at),
            "dueAt": rfc3339_utc(self.due_at),
            "timezonePolicy": self.timezone_policy,
        }


@dataclass(frozen=True, slots=True)
class AgreementPriceRef:
    fare_rule_refs: tuple[str, ...]
    rule_set_id: str | None = None
    rule_set_version: str | None = None

    def __post_init__(self) -> None:
        refs = tuple(ref.strip() for ref in self.fare_rule_refs if ref.strip())
        if not refs and not (self.rule_set_id and self.rule_set_id.strip()):
            raise CorporateTravelError("at least one agreement price reference is required")
        object.__setattr__(self, "fare_rule_refs", refs)
        if self.rule_set_id is not None and not self.rule_set_id.strip():
            raise CorporateTravelError("rule_set_id cannot be blank")
        if self.rule_set_version is not None and not self.rule_set_version.strip():
            raise CorporateTravelError("rule_set_version cannot be blank")

    @property
    def digest(self) -> str:
        return material_hash(self.to_dict())

    def to_dict(self) -> dict[str, Any]:
        return {
            "fareRuleRefs": list(self.fare_rule_refs),
            "ruleSetId": self.rule_set_id,
            "ruleSetVersion": self.rule_set_version,
        }


@dataclass(frozen=True, slots=True)
class AuthorizedTraveler:
    authorization_id: str
    account_id: str
    corporate_id: str
    agreement_id: str
    cost_center: str
    project_code: str | None
    scope: Mapping[str, Any]
    max_trip_amount: Money | None
    can_delegate_booking: bool
    valid_from: datetime
    valid_until: datetime
    status: AuthorizationStatus = AuthorizationStatus.ACTIVE
    version: int = 1

    def __post_init__(self) -> None:
        _require_text(self.authorization_id, "authorization_id")
        _require_text(self.account_id, "account_id")
        _require_text(self.corporate_id, "corporate_id")
        _require_text(self.agreement_id, "agreement_id")
        _require_text(self.cost_center, "cost_center")
        starts = _coerce_utc(self.valid_from)
        ends = _coerce_utc(self.valid_until)
        if ends <= starts:
            raise CorporateTravelError("authorization valid_until must be after valid_from")
        if self.version < 1:
            raise CorporateTravelError("authorization version must be positive")
        object.__setattr__(self, "valid_from", starts)
        object.__setattr__(self, "valid_until", ends)
        object.__setattr__(self, "scope", dict(self.scope))

    def is_active_at(self, at: datetime) -> bool:
        checked = _coerce_utc(at)
        return self.status is AuthorizationStatus.ACTIVE and self.valid_from <= checked < self.valid_until

    def snapshot_ref(self) -> str:
        return f"auth-snap-{self.authorization_id}-v{self.version}"

    def suspend(self) -> Self:
        if self.status in {AuthorizationStatus.REVOKED, AuthorizationStatus.EXPIRED, AuthorizationStatus.FAILED}:
            raise CorporateTravelError("terminal authorization cannot be suspended")
        return replace(self, status=AuthorizationStatus.SUSPENDED, version=self.version + 1)

    def revoke(self) -> Self:
        if self.status in {AuthorizationStatus.REVOKED, AuthorizationStatus.EXPIRED, AuthorizationStatus.FAILED}:
            raise CorporateTravelError("terminal authorization cannot be revoked again")
        return replace(self, status=AuthorizationStatus.REVOKED, version=self.version + 1)

    def to_dict(self) -> dict[str, Any]:
        return {
            "authorizationId": self.authorization_id,
            "accountId": self.account_id,
            "corporateId": self.corporate_id,
            "agreementId": self.agreement_id,
            "costCenter": self.cost_center,
            "projectCode": self.project_code,
            "scope": dict(self.scope),
            "maxTripAmount": self.max_trip_amount.to_dict() if self.max_trip_amount else None,
            "canDelegateBooking": self.can_delegate_booking,
            "validFrom": rfc3339_utc(self.valid_from),
            "validUntil": rfc3339_utc(self.valid_until),
            "status": self.status.value,
            "version": self.version,
            "authorizationSnapshotRef": self.snapshot_ref(),
        }


@dataclass(frozen=True, slots=True)
class CorporateAgreement:
    agreement_id: str
    corporate_id: str
    agreement_code: str
    legal_name: str
    version: int
    effective_window: EffectiveWindow
    price_ref: AgreementPriceRef
    monthly_credit_limit: Money
    billing_calendar: BillingCalendar
    contact: Mapping[str, str]
    status: AgreementStatus = AgreementStatus.DRAFT
    authorized_travelers: tuple[AuthorizedTraveler, ...] = field(default_factory=tuple)
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    activated_at: datetime | None = None

    def __post_init__(self) -> None:
        _require_text(self.agreement_id, "agreement_id")
        _require_text(self.corporate_id, "corporate_id")
        _require_text(self.agreement_code, "agreement_code")
        _require_text(self.legal_name, "legal_name")
        if self.version < 1:
            raise CorporateTravelError("agreement version must be positive")
        object.__setattr__(self, "created_at", _coerce_utc(self.created_at))
        if self.activated_at is not None:
            object.__setattr__(self, "activated_at", _coerce_utc(self.activated_at))
        object.__setattr__(self, "contact", dict(self.contact))
        currencies = {traveler.max_trip_amount.currency for traveler in self.authorized_travelers if traveler.max_trip_amount is not None}
        if currencies and currencies != {self.monthly_credit_limit.currency}:
            raise CorporateTravelError("authorized traveler max trip amounts must use agreement currency")

    def activate(self, *, at: datetime | None = None) -> Self:
        if self.status is not AgreementStatus.DRAFT:
            raise CorporateTravelError("only DRAFT agreements can be activated")
        activated_at = _coerce_utc(at or datetime.now(UTC))
        if activated_at >= self.effective_window.ends_at:
            raise CorporateTravelError("cannot activate after effective window end")
        return replace(self, status=AgreementStatus.ACTIVE, activated_at=activated_at)

    def authorize_traveler(self, traveler: AuthorizedTraveler, *, at: datetime | None = None) -> Self:
        checked_at = _coerce_utc(at or datetime.now(UTC))
        if self.status is not AgreementStatus.ACTIVE:
            raise CorporateTravelError("only ACTIVE agreements can authorize travelers")
        if not self.effective_window.contains(checked_at):
            raise CorporateTravelError("agreement is outside its effective window")
        if traveler.corporate_id != self.corporate_id or traveler.agreement_id != self.agreement_id:
            raise CorporateTravelError("traveler authorization must belong to agreement")
        if traveler.max_trip_amount is not None and traveler.max_trip_amount.currency != self.monthly_credit_limit.currency:
            raise CorporateTravelError("traveler max trip amount currency must match agreement")
        for existing in self.authorized_travelers:
            if (
                existing.account_id == traveler.account_id
                and existing.status is AuthorizationStatus.ACTIVE
                and traveler.status is AuthorizationStatus.ACTIVE
                and windows_overlap(existing.valid_from, existing.valid_until, traveler.valid_from, traveler.valid_until)
                and material_hash(existing.scope) == material_hash(traveler.scope)
            ):
                raise CorporateTravelError("active authorization already exists for account, agreement, and scope")
        return replace(self, authorized_travelers=self.authorized_travelers + (traveler,))

    def to_dict(self) -> dict[str, Any]:
        return {
            "agreementId": self.agreement_id,
            "corporateId": self.corporate_id,
            "agreementCode": self.agreement_code,
            "legalName": self.legal_name,
            "agreementVersion": self.version,
            "effectiveWindow": self.effective_window.to_dict(),
            "priceRef": self.price_ref.to_dict(),
            "agreementPriceRefDigest": self.price_ref.digest,
            "monthlyCreditLimit": self.monthly_credit_limit.to_dict(),
            "billingCalendar": self.billing_calendar.to_dict(),
            "contact": dict(self.contact),
            "status": self.status.value,
            "authorizedTravelers": [traveler.to_dict() for traveler in self.authorized_travelers],
            "createdAt": rfc3339_utc(self.created_at),
            "activatedAt": rfc3339_utc(self.activated_at) if self.activated_at else None,
        }


@dataclass(frozen=True, slots=True)
class StatementLine:
    line_id: str
    source_type: StatementLineSource
    source_business_ref: str
    order_id: str | None
    payment_intent_id: str | None
    amount: Money
    authorization_snapshot_ref: str | None
    source_event_id: str
    occurred_at: datetime

    def __post_init__(self) -> None:
        _require_text(self.line_id, "line_id")
        _require_text(self.source_business_ref, "source_business_ref")
        _require_text(self.source_event_id, "source_event_id")
        object.__setattr__(self, "occurred_at", _coerce_utc(self.occurred_at))

    def to_dict(self) -> dict[str, Any]:
        return {
            "lineId": self.line_id,
            "sourceType": self.source_type.value,
            "sourceBusinessRef": self.source_business_ref,
            "orderId": self.order_id,
            "paymentIntentId": self.payment_intent_id,
            "amount": self.amount.to_dict(),
            "authorizationSnapshotRef": self.authorization_snapshot_ref,
            "sourceEventId": self.source_event_id,
            "occurredAt": rfc3339_utc(self.occurred_at),
        }


@dataclass(frozen=True, slots=True)
class BillingPeriod:
    statement_id: str
    corporate_id: str
    agreement_id: str
    billing_period: str
    currency: str
    cutoff_at: datetime
    due_at: datetime
    status: BillingPeriodStatus = BillingPeriodStatus.OPEN
    lines: tuple[StatementLine, ...] = field(default_factory=tuple)
    statement_hash: str | None = None
    closed_at: datetime | None = None

    def __post_init__(self) -> None:
        _require_text(self.statement_id, "statement_id")
        _require_text(self.corporate_id, "corporate_id")
        _require_text(self.agreement_id, "agreement_id")
        _require_text(self.billing_period, "billing_period")
        currency = self.currency.strip().upper()
        if len(currency) != 3 or not currency.isalpha():
            raise CorporateTravelError("currency must be an ISO-4217 code")
        cutoff = _coerce_utc(self.cutoff_at)
        due = _coerce_utc(self.due_at)
        if due <= cutoff:
            raise CorporateTravelError("billing period due_at must be after cutoff_at")
        if any(line.amount.currency != currency for line in self.lines):
            raise CorporateTravelError("statement lines must match billing period currency")
        object.__setattr__(self, "currency", currency)
        object.__setattr__(self, "cutoff_at", cutoff)
        object.__setattr__(self, "due_at", due)
        if self.closed_at is not None:
            object.__setattr__(self, "closed_at", _coerce_utc(self.closed_at))

    def attach_line(self, line: StatementLine) -> Self:
        if self.status is not BillingPeriodStatus.OPEN:
            raise CorporateTravelError("only OPEN billing periods can receive lines")
        if line.amount.currency != self.currency:
            raise CorporateTravelError("line currency must match billing period")
        if any(existing.source_event_id == line.source_event_id for existing in self.lines):
            return self
        return replace(self, lines=self.lines + (line,))

    def freeze(self) -> Self:
        if self.status is not BillingPeriodStatus.OPEN:
            raise CorporateTravelError("only OPEN billing periods can be frozen")
        return replace(self, status=BillingPeriodStatus.FROZEN, statement_hash=material_hash(self.to_statement_material()))

    def close(self, *, at: datetime | None = None) -> Self:
        if self.status not in {BillingPeriodStatus.FROZEN, BillingPeriodStatus.ACCEPTED}:
            raise CorporateTravelError("only FROZEN or ACCEPTED billing periods can be closed")
        frozen = self if self.statement_hash else self.freeze()
        return replace(frozen, status=BillingPeriodStatus.CLOSED, closed_at=_coerce_utc(at or datetime.now(UTC)))

    @property
    def total_amount(self) -> Money:
        return Money(self.currency, sum(line.amount.minor_units for line in self.lines))

    def to_statement_material(self) -> dict[str, Any]:
        return {
            "statementId": self.statement_id,
            "corporateId": self.corporate_id,
            "agreementId": self.agreement_id,
            "billingPeriod": self.billing_period,
            "currency": self.currency,
            "lines": [line.to_dict() for line in sorted(self.lines, key=lambda item: item.line_id)],
            "totalAmount": self.total_amount.to_dict(),
        }

    def to_dict(self) -> dict[str, Any]:
        return {
            **self.to_statement_material(),
            "cutoffAt": rfc3339_utc(self.cutoff_at),
            "dueAt": rfc3339_utc(self.due_at),
            "status": self.status.value,
            "statementHash": self.statement_hash,
            "closedAt": rfc3339_utc(self.closed_at) if self.closed_at else None,
        }


class SeatClass(str, Enum):
    SECOND_CLASS = "SECOND_CLASS"
    FIRST_CLASS = "FIRST_CLASS"


class EmployeeLevel(str, Enum):
    STAFF = "STAFF"
    MANAGER = "MANAGER"
    DIRECTOR = "DIRECTOR"
    VP = "VP"
    C_LEVEL = "C_LEVEL"


class PolicyDecision(str, Enum):
    COMPLIANT = "COMPLIANT"
    NEEDS_APPROVAL = "NEEDS_APPROVAL"
    REJECTED = "REJECTED"


class ApprovalLevel(int, Enum):
    DIRECT_MANAGER = 1
    DEPARTMENT_HEAD = 2
    FINANCE = 3


class ApprovalStatus(str, Enum):
    PENDING = "PENDING"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    ESCALATED = "ESCALATED"


class ApprovalDecisionValue(str, Enum):
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    ESCALATED = "ESCALATED"


class BudgetDimension(str, Enum):
    EMPLOYEE = "EMPLOYEE"
    DEPARTMENT = "DEPARTMENT"
    AGREEMENT = "AGREEMENT"


class BudgetReservationStatus(str, Enum):
    RESERVED = "RESERVED"
    COMMITTED = "COMMITTED"
    RELEASED = "RELEASED"


@dataclass(frozen=True, slots=True)
class EmployeeProfile:
    employee_ref: str
    department_ref: str
    level: EmployeeLevel = EmployeeLevel.STAFF
    manager_ref: str | None = None
    display_name: str | None = None

    def __post_init__(self) -> None:
        _require_text(self.employee_ref, "employee_ref")
        _require_text(self.department_ref, "department_ref")

    @property
    def is_vp_or_above(self) -> bool:
        return self.level in {EmployeeLevel.VP, EmployeeLevel.C_LEVEL}

    def to_dict(self) -> dict[str, Any]:
        return {
            "employeeRef": self.employee_ref,
            "departmentRef": self.department_ref,
            "level": self.level.value,
            "managerRef": self.manager_ref,
            "displayName": self.display_name,
        }


@dataclass(frozen=True, slots=True)
class BookingRequest:
    booking_ref: str
    agreement_id: str
    employee_ref: str
    department_ref: str
    origin: str
    destination: str
    seat_class: SeatClass
    amount: Money
    requested_at: datetime
    departure_at: datetime
    trip_duration_minutes: int
    emergency: bool = False

    def __post_init__(self) -> None:
        _require_text(self.booking_ref, "booking_ref")
        _require_text(self.agreement_id, "agreement_id")
        _require_text(self.employee_ref, "employee_ref")
        _require_text(self.department_ref, "department_ref")
        _require_text(self.origin, "origin")
        _require_text(self.destination, "destination")
        if self.trip_duration_minutes < 0:
            raise CorporateTravelError("trip_duration_minutes must be non-negative")
        object.__setattr__(self, "requested_at", _coerce_utc(self.requested_at))
        object.__setattr__(self, "departure_at", _coerce_utc(self.departure_at))

    @property
    def route_pair(self) -> tuple[str, str]:
        return (self.origin.strip().upper(), self.destination.strip().upper())

    @property
    def duration_exceeds_six_hours(self) -> bool:
        return self.trip_duration_minutes > 6 * 60

    def to_dict(self) -> dict[str, Any]:
        return {
            "bookingRef": self.booking_ref,
            "agreementId": self.agreement_id,
            "employeeRef": self.employee_ref,
            "departmentRef": self.department_ref,
            "origin": self.origin,
            "destination": self.destination,
            "seatClass": self.seat_class.value,
            "amount": self.amount.to_dict(),
            "requestedAt": rfc3339_utc(self.requested_at),
            "departureAt": rfc3339_utc(self.departure_at),
            "tripDurationMinutes": self.trip_duration_minutes,
            "emergency": self.emergency,
        }


@dataclass(frozen=True, slots=True)
class PolicyViolation:
    rule_type: str
    decision: PolicyDecision
    message: str
    approval_level: ApprovalLevel | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "ruleType": self.rule_type,
            "decision": self.decision.value,
            "message": self.message,
            "approvalLevel": self.approval_level.value if self.approval_level else None,
        }


@dataclass(frozen=True, slots=True)
class PolicyResult:
    decision: PolicyDecision
    violations: tuple[PolicyViolation, ...] = field(default_factory=tuple)

    @property
    def requires_level(self) -> ApprovalLevel | None:
        levels = [violation.approval_level for violation in self.violations if violation.approval_level is not None]
        return max(levels, key=lambda level: level.value) if levels else None

    def to_dict(self) -> dict[str, Any]:
        return {"result": self.decision.value, "violations": [violation.to_dict() for violation in self.violations]}


class PolicyRule:
    rule_type: str

    def check(self, booking: BookingRequest, employee: EmployeeProfile, budget_context: Mapping[str, int]) -> PolicyViolation | None:
        raise NotImplementedError

    def to_dict(self) -> dict[str, Any]:
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class SeatClassLimit(PolicyRule):
    default_class: SeatClass = SeatClass.SECOND_CLASS
    vp_exception: bool = True
    long_trip_minutes: int = 6 * 60
    rule_type: str = "SEAT_CLASS_LIMIT"

    def check(self, booking: BookingRequest, employee: EmployeeProfile, budget_context: Mapping[str, int]) -> PolicyViolation | None:
        del budget_context
        if booking.seat_class is not SeatClass.FIRST_CLASS:
            return None
        if self.vp_exception and employee.is_vp_or_above:
            return None
        if booking.trip_duration_minutes > self.long_trip_minutes:
            return None
        return PolicyViolation(self.rule_type, PolicyDecision.NEEDS_APPROVAL, "FIRST_CLASS requires VP+ level or trip longer than 6 hours", ApprovalLevel.DEPARTMENT_HEAD)

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.rule_type, "default": self.default_class.value, "vpException": self.vp_exception, "longTripMinutes": self.long_trip_minutes}


@dataclass(frozen=True, slots=True)
class AdvanceBookingRule(PolicyRule):
    minimum_days: int = 3
    emergency_requires_approval: bool = True
    rule_type: str = "ADVANCE_BOOKING"

    def __post_init__(self) -> None:
        if self.minimum_days < 0:
            raise CorporateTravelError("minimum_days must be non-negative")

    def check(self, booking: BookingRequest, employee: EmployeeProfile, budget_context: Mapping[str, int]) -> PolicyViolation | None:
        del employee, budget_context
        if booking.departure_at - booking.requested_at >= timedelta(days=self.minimum_days):
            return None
        if booking.emergency and self.emergency_requires_approval:
            return PolicyViolation(self.rule_type, PolicyDecision.NEEDS_APPROVAL, "emergency travel inside advance-booking window requires manager approval", ApprovalLevel.DIRECT_MANAGER)
        return PolicyViolation(self.rule_type, PolicyDecision.NEEDS_APPROVAL, "booking must be made at least 3 days before departure", ApprovalLevel.DIRECT_MANAGER)

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.rule_type, "minimumDays": self.minimum_days, "emergencyRequiresApproval": self.emergency_requires_approval}


@dataclass(frozen=True, slots=True)
class RouteRestriction(PolicyRule):
    allowed_routes: tuple[tuple[str, str], ...] = field(default_factory=tuple)
    require_approval_for_unlisted: bool = True
    rule_type: str = "ROUTE_RESTRICTION"

    def __post_init__(self) -> None:
        routes = tuple((origin.strip().upper(), destination.strip().upper()) for origin, destination in self.allowed_routes)
        object.__setattr__(self, "allowed_routes", routes)

    def check(self, booking: BookingRequest, employee: EmployeeProfile, budget_context: Mapping[str, int]) -> PolicyViolation | None:
        del employee, budget_context
        if not self.allowed_routes or booking.route_pair in self.allowed_routes:
            return None
        decision = PolicyDecision.NEEDS_APPROVAL if self.require_approval_for_unlisted else PolicyDecision.REJECTED
        level = ApprovalLevel.DEPARTMENT_HEAD if self.require_approval_for_unlisted else None
        return PolicyViolation(self.rule_type, decision, "route is not approved for this corporate agreement", level)

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.rule_type, "allowedRoutes": [[o, d] for o, d in self.allowed_routes], "requireApprovalForUnlisted": self.require_approval_for_unlisted}


@dataclass(frozen=True, slots=True)
class BudgetLimit(PolicyRule):
    per_trip_minor: int = 200_000
    per_employee_monthly_minor: int = 800_000
    per_department_monthly_minor: int = 10_000_000
    rule_type: str = "BUDGET_LIMIT"

    def __post_init__(self) -> None:
        if min(self.per_trip_minor, self.per_employee_monthly_minor, self.per_department_monthly_minor) < 0:
            raise CorporateTravelError("budget limits must be non-negative")

    def check(self, booking: BookingRequest, employee: EmployeeProfile, budget_context: Mapping[str, int]) -> PolicyViolation | None:
        del employee
        department_used = int(budget_context.get("departmentMonthlyUsedMinor", 0)) + booking.amount.minor_units
        if department_used > self.per_department_monthly_minor:
            return PolicyViolation(self.rule_type, PolicyDecision.REJECTED, "department monthly budget exceeded; Level 3 approval required", ApprovalLevel.FINANCE)
        if booking.amount.minor_units > self.per_trip_minor:
            return PolicyViolation(self.rule_type, PolicyDecision.NEEDS_APPROVAL, "trip exceeds per-trip budget limit", ApprovalLevel.DEPARTMENT_HEAD)
        employee_used = int(budget_context.get("employeeMonthlyUsedMinor", 0)) + booking.amount.minor_units
        if employee_used > self.per_employee_monthly_minor:
            return PolicyViolation(self.rule_type, PolicyDecision.NEEDS_APPROVAL, "employee monthly budget exceeded", ApprovalLevel.DEPARTMENT_HEAD)
        return None

    def to_dict(self) -> dict[str, Any]:
        return {"type": self.rule_type, "perTripMinor": self.per_trip_minor, "perEmployeeMonthlyMinor": self.per_employee_monthly_minor, "perDepartmentMonthlyMinor": self.per_department_monthly_minor}


@dataclass(frozen=True, slots=True)
class TravelPolicy:
    agreement_id: str
    rules: tuple[PolicyRule, ...]

    def __post_init__(self) -> None:
        _require_text(self.agreement_id, "agreement_id")
        if not self.rules:
            raise CorporateTravelError("travel policy requires at least one rule")

    @classmethod
    def default(cls, agreement_id: str, *, allowed_routes: tuple[tuple[str, str], ...] = ()) -> Self:
        return cls(agreement_id=agreement_id, rules=(SeatClassLimit(), AdvanceBookingRule(), RouteRestriction(allowed_routes), BudgetLimit()))

    def to_dict(self) -> dict[str, Any]:
        return {"agreementId": self.agreement_id, "rules": [rule.to_dict() for rule in self.rules]}


class PolicyChecker:
    def check(self, booking: BookingRequest, employee: EmployeeProfile, policy: TravelPolicy, budget_context: Mapping[str, int] | None = None) -> PolicyResult:
        if booking.agreement_id != policy.agreement_id:
            raise CorporateTravelError("booking agreement must match policy")
        if booking.employee_ref != employee.employee_ref or booking.department_ref != employee.department_ref:
            raise CorporateTravelError("booking employee and department must match employee profile")
        violations = tuple(filter(None, (rule.check(booking, employee, budget_context or {}) for rule in policy.rules)))
        if any(violation.decision is PolicyDecision.REJECTED for violation in violations):
            return PolicyResult(PolicyDecision.REJECTED, violations)
        if violations:
            return PolicyResult(PolicyDecision.NEEDS_APPROVAL, violations)
        return PolicyResult(PolicyDecision.COMPLIANT, ())


@dataclass(frozen=True, slots=True)
class ApprovalDecision:
    level: ApprovalLevel
    approver_ref: str
    decision: ApprovalDecisionValue
    reason: str
    decided_at: datetime

    def __post_init__(self) -> None:
        _require_text(self.approver_ref, "approver_ref")
        object.__setattr__(self, "decided_at", _coerce_utc(self.decided_at))

    def to_dict(self) -> dict[str, Any]:
        return {"level": self.level.value, "approverRef": self.approver_ref, "decision": self.decision.value, "reason": self.reason, "decidedAt": rfc3339_utc(self.decided_at)}


@dataclass(frozen=True, slots=True)
class ApprovalRequest:
    request_id: str
    booking_ref: str
    employee_ref: str
    current_level: ApprovalLevel
    status: ApprovalStatus = ApprovalStatus.PENDING
    history: tuple[ApprovalDecision, ...] = field(default_factory=tuple)
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))

    def __post_init__(self) -> None:
        _require_text(self.request_id, "request_id")
        _require_text(self.booking_ref, "booking_ref")
        _require_text(self.employee_ref, "employee_ref")
        object.__setattr__(self, "created_at", _coerce_utc(self.created_at))

    @classmethod
    def for_policy_result(cls, request_id: str, booking_ref: str, employee_ref: str, result: PolicyResult) -> Self:
        level = result.requires_level or ApprovalLevel.DIRECT_MANAGER
        status = ApprovalStatus.APPROVED if result.decision is PolicyDecision.COMPLIANT else ApprovalStatus.PENDING
        return cls(request_id=request_id, booking_ref=booking_ref, employee_ref=employee_ref, current_level=level, status=status)

    def decide(self, decision: ApprovalDecision) -> Self:
        if self.status not in {ApprovalStatus.PENDING, ApprovalStatus.ESCALATED}:
            raise CorporateTravelError("terminal approval request cannot be decided")
        if decision.level != self.current_level:
            raise CorporateTravelError("approval decision level must match current level")
        history = self.history + (decision,)
        if decision.decision is ApprovalDecisionValue.REJECTED:
            return replace(self, status=ApprovalStatus.REJECTED, history=history)
        if decision.decision is ApprovalDecisionValue.ESCALATED:
            if self.current_level is ApprovalLevel.FINANCE:
                raise CorporateTravelError("finance approval cannot be escalated")
            return replace(self, current_level=ApprovalLevel(self.current_level.value + 1), status=ApprovalStatus.ESCALATED, history=history)
        return replace(self, status=ApprovalStatus.APPROVED, history=history)

    def to_dict(self) -> dict[str, Any]:
        return {"requestId": self.request_id, "bookingRef": self.booking_ref, "employeeRef": self.employee_ref, "currentLevel": self.current_level.value, "status": self.status.value, "history": [item.to_dict() for item in self.history], "createdAt": rfc3339_utc(self.created_at)}


@dataclass(frozen=True, slots=True)
class BudgetReservation:
    reservation_id: str
    pool_id: str
    booking_ref: str
    amount_minor: int
    status: BudgetReservationStatus = BudgetReservationStatus.RESERVED

    def __post_init__(self) -> None:
        _require_text(self.reservation_id, "reservation_id")
        _require_text(self.pool_id, "pool_id")
        _require_text(self.booking_ref, "booking_ref")
        if self.amount_minor < 0:
            raise CorporateTravelError("reservation amount_minor must be non-negative")

    def commit(self) -> Self:
        if self.status is not BudgetReservationStatus.RESERVED:
            raise CorporateTravelError("only RESERVED budget reservations can be committed")
        return replace(self, status=BudgetReservationStatus.COMMITTED)

    def release(self) -> Self:
        if self.status is BudgetReservationStatus.RELEASED:
            return self
        return replace(self, status=BudgetReservationStatus.RELEASED)

    def to_dict(self) -> dict[str, Any]:
        return {"reservationId": self.reservation_id, "poolId": self.pool_id, "bookingRef": self.booking_ref, "amountMinor": self.amount_minor, "status": self.status.value}


@dataclass(frozen=True, slots=True)
class BudgetPool:
    pool_id: str
    dimension: BudgetDimension
    period_start: datetime
    period_end: datetime
    limit_minor: int
    reserved_minor: int = 0
    committed_minor: int = 0
    reservations: tuple[BudgetReservation, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        _require_text(self.pool_id, "pool_id")
        start = _coerce_utc(self.period_start)
        end = _coerce_utc(self.period_end)
        if end <= start:
            raise CorporateTravelError("budget period_end must be after period_start")
        if min(self.limit_minor, self.reserved_minor, self.committed_minor) < 0:
            raise CorporateTravelError("budget amounts must be non-negative")
        object.__setattr__(self, "period_start", start)
        object.__setattr__(self, "period_end", end)

    @property
    def utilized_minor(self) -> int:
        return self.reserved_minor + self.committed_minor

    @property
    def utilization_ratio(self) -> float:
        return 1.0 if self.limit_minor == 0 and self.utilized_minor > 0 else (self.utilized_minor / self.limit_minor if self.limit_minor else 0.0)

    def reserve(self, reservation_id: str, booking_ref: str, amount_minor: int, *, allow_over_limit: bool = False) -> Self:
        if amount_minor < 0:
            raise CorporateTravelError("reservation amount_minor must be non-negative")
        if not allow_over_limit and self.utilized_minor + amount_minor > self.limit_minor:
            raise CorporateTravelError("budget limit exceeded")
        if any(item.booking_ref == booking_ref and item.status is BudgetReservationStatus.RESERVED for item in self.reservations):
            return self
        reservation = BudgetReservation(reservation_id, self.pool_id, booking_ref, amount_minor)
        return replace(self, reserved_minor=self.reserved_minor + amount_minor, reservations=self.reservations + (reservation,))

    def commit(self, booking_ref: str) -> Self:
        reservations: list[BudgetReservation] = []
        committed = 0
        reserved = self.reserved_minor
        found = False
        for reservation in self.reservations:
            if reservation.booking_ref == booking_ref and reservation.status is BudgetReservationStatus.RESERVED:
                found = True
                reservations.append(reservation.commit())
                reserved -= reservation.amount_minor
                committed += reservation.amount_minor
            else:
                reservations.append(reservation)
        if not found:
            return self
        return replace(self, reserved_minor=reserved, committed_minor=self.committed_minor + committed, reservations=tuple(reservations))

    def release(self, booking_ref: str) -> Self:
        reservations: list[BudgetReservation] = []
        reserved = self.reserved_minor
        committed = self.committed_minor
        found = False
        for reservation in self.reservations:
            if reservation.booking_ref == booking_ref and reservation.status is not BudgetReservationStatus.RELEASED:
                found = True
                if reservation.status is BudgetReservationStatus.RESERVED:
                    reserved -= reservation.amount_minor
                if reservation.status is BudgetReservationStatus.COMMITTED:
                    committed -= reservation.amount_minor
                reservations.append(reservation.release())
            else:
                reservations.append(reservation)
        if not found:
            return self
        return replace(self, reserved_minor=reserved, committed_minor=committed, reservations=tuple(reservations))

    def alert_threshold(self) -> int | None:
        if self.utilization_ratio >= 1:
            return 100
        if self.utilization_ratio >= 0.8:
            return 80
        return None

    def to_dict(self) -> dict[str, Any]:
        return {"poolId": self.pool_id, "dimension": self.dimension.value, "periodStart": rfc3339_utc(self.period_start), "periodEnd": rfc3339_utc(self.period_end), "limitMinor": self.limit_minor, "reservedMinor": self.reserved_minor, "committedMinor": self.committed_minor, "reservations": [item.to_dict() for item in self.reservations]}


@dataclass(frozen=True, slots=True)
class InvoiceLineItem:
    booking_ref: str
    employee_name: str
    route: str
    travel_date: datetime
    amount_minor: int

    def __post_init__(self) -> None:
        _require_text(self.booking_ref, "booking_ref")
        _require_text(self.employee_name, "employee_name")
        _require_text(self.route, "route")
        if self.amount_minor < 0:
            raise CorporateTravelError("invoice amount_minor must be non-negative")
        object.__setattr__(self, "travel_date", _coerce_utc(self.travel_date))

    def to_dict(self) -> dict[str, Any]:
        return {"bookingRef": self.booking_ref, "employeeName": self.employee_name, "route": self.route, "travelDate": rfc3339_utc(self.travel_date), "amountMinor": self.amount_minor}


@dataclass(frozen=True, slots=True)
class MonthlyInvoice:
    invoice_id: str
    agreement_id: str
    period: str
    line_items: tuple[InvoiceLineItem, ...]
    total_minor: int
    discount_minor: int = 0

    def __post_init__(self) -> None:
        _require_text(self.invoice_id, "invoice_id")
        _require_text(self.agreement_id, "agreement_id")
        _require_text(self.period, "period")
        if min(self.total_minor, self.discount_minor) < 0:
            raise CorporateTravelError("invoice totals must be non-negative")
        expected_total = max(sum(item.amount_minor for item in self.line_items) - self.discount_minor, 0)
        if self.total_minor != expected_total:
            raise CorporateTravelError("invoice total must equal line item sum minus discount")

    @classmethod
    def generate(cls, invoice_id: str, agreement_id: str, period: str, line_items: tuple[InvoiceLineItem, ...], discount_rate_bps: int = 0) -> Self:
        if discount_rate_bps < 0 or discount_rate_bps > 10_000:
            raise CorporateTravelError("discount_rate_bps must be between 0 and 10000")
        gross = sum(item.amount_minor for item in line_items)
        discount = gross * discount_rate_bps // 10_000
        return cls(invoice_id, agreement_id, period, line_items, gross - discount, discount)

    def to_dict(self) -> dict[str, Any]:
        return {"invoiceId": self.invoice_id, "agreementId": self.agreement_id, "period": self.period, "lineItems": [item.to_dict() for item in self.line_items], "totalMinor": self.total_minor, "discountMinor": self.discount_minor}


def rfc3339_utc(value: datetime) -> str:
    aware = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def material_hash(value: Mapping[str, Any]) -> str:
    return sha256(json.dumps(_normalize(value), ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")).hexdigest()


def windows_overlap(start_a: datetime, end_a: datetime, start_b: datetime, end_b: datetime) -> bool:
    return _coerce_utc(start_a) < _coerce_utc(end_b) and _coerce_utc(start_b) < _coerce_utc(end_a)


def _normalize(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {str(key): _normalize(item) for key, item in sorted(value.items(), key=lambda pair: str(pair[0]))}
    if isinstance(value, (list, tuple)):
        return [_normalize(item) for item in value]
    if isinstance(value, datetime):
        return rfc3339_utc(value)
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, str):
        return value.strip()
    return value


def _coerce_utc(value: datetime) -> datetime:
    return (value if value.tzinfo is not None else value.replace(tzinfo=UTC)).astimezone(UTC)


def _require_text(value: str, field_name: str) -> None:
    if not value.strip():
        raise CorporateTravelError(f"{field_name} is required")
