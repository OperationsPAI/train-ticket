from __future__ import annotations

import json
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
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
