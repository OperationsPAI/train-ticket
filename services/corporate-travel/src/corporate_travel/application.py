from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Mapping
from uuid import NAMESPACE_URL, uuid5

from train_ticket_platform.events import EventEnvelope, envelope_factory
from train_ticket_platform.ids import new_prefixed_uuid7, new_uuid7
from train_ticket_platform.messaging import EventPublisher, InMemoryEventPublisher

from .domain import (
    AgreementPriceRef,
    AuthorizedTraveler,
    BillingCalendar,
    BillingPeriod,
    CorporateAgreement,
    EffectiveWindow,
    Money,
    StatementLine,
    StatementLineSource,
)

PRODUCER = "corporate-travel"
SCHEMA_VERSION = 1


class AgreementNotFoundError(KeyError):
    pass


class BillingPeriodNotFoundError(KeyError):
    pass


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
    order_confirmations: dict[str, EventEnvelope] = field(default_factory=dict)
    payment_captures_by_order: dict[str, list[EventEnvelope]] = field(default_factory=dict)
    billed_payment_event_ids: set[str] = field(default_factory=set)

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

    def try_mark_processed(self, event_id: str) -> bool:
        if event_id in self.processed_event_ids:
            return False
        self.processed_event_ids.add(event_id)
        return True

    def remember_order_confirmed(self, order_id: str, envelope: EventEnvelope) -> None:
        self.order_confirmations[order_id] = envelope

    def order_confirmed(self, order_id: str) -> bool:
        return order_id in self.order_confirmations

    def remember_payment_capture(self, order_id: str, envelope: EventEnvelope) -> None:
        captures = self.payment_captures_by_order.setdefault(order_id, [])
        if all(capture.eventId != envelope.eventId for capture in captures):
            captures.append(envelope)

    def unbilled_payment_captures(self, order_id: str) -> tuple[EventEnvelope, ...]:
        return tuple(
            capture
            for capture in self.payment_captures_by_order.get(order_id, ())
            if capture.eventId not in self.billed_payment_event_ids
        )

    def mark_payment_billed(self, event_id: str) -> None:
        self.billed_payment_event_ids.add(event_id)


@dataclass(slots=True)
class CorporateTravelService:
    publisher: EventPublisher = field(default_factory=InMemoryEventPublisher)
    repository: InMemoryCorporateTravelRepository = field(default_factory=InMemoryCorporateTravelRepository)

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
            self.publisher.publish(created_event)
            self.publisher.publish(activated_event)
            return CorporateAgreementResult(agreement)
        self.repository.save_agreement(agreement)
        self.publisher.publish(created_event)
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
        self.publisher.publish(_authorization_event(traveler, correlation_id, causation_id))
        return traveler

    def handle_event(self, envelope: EventEnvelope) -> None:
        if envelope.eventType not in {"JourneyOrderConfirmed", "PaymentCaptured"}:
            return
        if not self.repository.try_mark_processed(envelope.eventId):
            return

        order_id = order_id_from_event(envelope)
        if order_id is None:
            return

        if envelope.eventType == "JourneyOrderConfirmed":
            self.repository.remember_order_confirmed(order_id, envelope)
        else:
            self.repository.remember_payment_capture(order_id, envelope)

        if not self.repository.order_confirmed(order_id):
            return

        for payment in self.repository.unbilled_payment_captures(order_id):
            self._attach_captured_payment(payment)
            self.repository.mark_payment_billed(payment.eventId)

    def _attach_captured_payment(self, envelope: EventEnvelope) -> None:
        line = line_from_event(envelope)
        agreement = self.repository.get_agreement(str(envelope.payload["agreementId"]))
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

    def close_billing_period(
        self,
        *,
        agreement_id: str,
        billing_period: str,
        correlation_id: str | None = None,
        causation_id: str | None = None,
    ) -> BillingPeriodResult:
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
        self.publisher.publish(_billing_event(closed, correlation_id, causation_id))
        return BillingPeriodResult(closed)


def line_from_event(envelope: EventEnvelope) -> StatementLine:
    if envelope.eventType != "PaymentCaptured":
        raise ValueError("only PaymentCaptured events create corporate billing statement lines")
    payload = envelope.payload
    amount = money_from_mapping(payload["capturedAmount"])
    order_id = order_id_from_event(envelope)
    payment_id = _optional_text(payload.get("paymentIntentId"))
    source_ref = payment_id or order_id or envelope.eventId
    return StatementLine(
        line_id=deterministic_prefixed_id("line", envelope.eventId),
        source_type=StatementLineSource.PAYMENT_CAPTURED,
        source_business_ref=source_ref,
        order_id=order_id,
        payment_intent_id=payment_id,
        amount=amount,
        authorization_snapshot_ref=_optional_text(payload.get("authorizationSnapshotRef")),
        source_event_id=envelope.eventId,
        occurred_at=parse_rfc3339(envelope.occurredAt) if isinstance(envelope.occurredAt, str) else envelope.occurredAt,
    )


def order_id_from_event(envelope: EventEnvelope) -> str | None:
    payload = envelope.payload
    return _optional_text(payload.get("orderId") or payload.get("businessRef"))


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


def _optional_text(value: object) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
