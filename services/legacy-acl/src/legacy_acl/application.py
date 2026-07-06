from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from train_ticket_platform.events import EventEnvelope, rfc3339_utc
from train_ticket_platform.messaging import EventPublisher, RedisEventPublisher

from .downstream import DownstreamClient, DownstreamError, quote
from .ids import deterministic_event_id, deterministic_prefixed_uuid, deterministic_uuid7


class LegacyOperation(StrEnum):
    PRESERVE = "PRESERVE"
    INSIDE_PAYMENT = "INSIDE_PAYMENT"
    TICKET_ISSUE = "TICKET_ISSUE"
    EXECUTE = "EXECUTE"
    CANCEL = "CANCEL"
    REBOOK = "REBOOK"


class Outcome(StrEnum):
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class LegacyContext:
    operator_ref: str
    reason: str | None
    source_ref: str
    correlation_id: str


@dataclass(frozen=True)
class LegacyResult:
    status: int
    msg: str
    data: Mapping[str, Any]


class LegacyAclService:
    def __init__(self, client: DownstreamClient | None = None, publisher: EventPublisher | None = None) -> None:
        self.client = client or DownstreamClient()
        self.publisher = publisher or RedisEventPublisher()

    def preserve(self, payload: Mapping[str, Any], ctx: LegacyContext) -> LegacyResult:
        commands: list[str] = []
        try:
            account_id = _required_text(payload, "accountId")
            traveler_ref = self._traveler_ref(account_id, payload.get("contactsId"), ctx, commands)
            search = self.client.post(
                "trip-planning",
                "/api/v1/itineraries/search",
                {
                    "originRef": _required_text(payload, "from"),
                    "destinationRef": _required_text(payload, "to"),
                    "departureDate": _required_text(payload, "date"),
                    "travelerRefs": [traveler_ref],
                    "channel": "WEB",
                },
                _headers(ctx, "search"),
            )
            commands.append("SearchItineraries")
            itinerary = _first_mapping(search, "itineraries", "no itinerary found")
            itinerary_ref = _required_text(itinerary, "itineraryRef")
            leg = _first_mapping(itinerary, "legs", "itinerary contains no legs")
            segment_ref = _required_text(leg, "serviceSegmentRef")

            fare_quote = self.client.post(
                "fare-pricing",
                "/api/v1/fare-quotes",
                {"travelerRefs": [traveler_ref], "channel": "WEB", "segmentRefs": [segment_ref]},
                _headers(ctx, "fare-quote"),
            )
            commands.append("CreateFareQuote")

            offer = self.client.post(
                "offer-management",
                "/api/v1/offers",
                {
                    "accountId": account_id,
                    "channelId": "WEB",
                    "itineraryRef": itinerary_ref,
                    "travelerRefs": [traveler_ref],
                    "quoteRequestId": str(fare_quote.get("quoteId", ctx.source_ref)),
                },
                _headers(ctx, "offer"),
            )
            commands.append("CreateOffer")
            offer_id = _required_text(offer, "offerId")
            offer_version = _required_int(offer, "offerVersion")

            order = self.client.post(
                "journey-order",
                "/api/v1/journey-orders",
                {
                    "accountId": account_id,
                    "offerId": offer_id,
                    "offerVersion": offer_version,
                    "travelerRefs": [traveler_ref],
                    "segmentRefs": [segment_ref],
                },
                _headers(ctx, "order"),
            )
            commands.append("CreateJourneyOrder")
            order_id = _required_text(order, "orderId")

            saga_id = self._resolve_saga(order_id, account_id, offer_id, [traveler_ref], [segment_ref], ctx, commands)
            segment_booking_id = deterministic_prefixed_uuid("sb", f"{order_id}:{segment_ref}:{traveler_ref}")
            self.client.post(
                "booking-orchestration",
                f"/api/v1/internal/booking-sagas/{quote(saga_id)}/request-reservation",
                {"segmentRef": segment_ref, "travelerRef": traveler_ref, "segmentBookingId": segment_booking_id},
                _headers(ctx, "reservation"),
            )
            commands.append("RequestSegmentReservation")

            result_refs = {
                "orderId": order_id,
                "offerId": offer_id,
                "total": offer.get("total") or _nested(fare_quote, "breakdown", "total"),
            }
            return self._finish(LegacyOperation.PRESERVE, Outcome.SUCCEEDED, ctx, commands, result_refs, None)
        except Exception as exc:
            return self._failure(LegacyOperation.PRESERVE, ctx, commands, exc)

    def inside_payment(self, payload: Mapping[str, Any], ctx: LegacyContext) -> LegacyResult:
        commands: list[str] = []
        try:
            order_id = _required_text(payload, "orderId")
            order = self._get_order(order_id)
            intent = self.client.post(
                "payment",
                "/api/v1/payment-intents",
                {
                    "businessRef": order_id,
                    "purpose": "purchase",
                    "amount": _required_mapping(payload, "price"),
                    "payerRef": _required_text(order, "accountId"),
                },
                _headers(ctx, "payment-intent"),
            )
            commands.append("CreatePaymentIntent")
            payment_intent_id = _required_text(intent, "paymentIntentId")
            self.client.post(
                "payment",
                f"/api/v1/payment-intents/{quote(payment_intent_id)}/capture",
                {},
                _headers(ctx, "capture"),
            )
            commands.append("CapturePayment")
            return self._finish(
                LegacyOperation.INSIDE_PAYMENT,
                Outcome.SUCCEEDED,
                ctx,
                commands,
                {"paymentIntentId": payment_intent_id},
                None,
            )
        except Exception as exc:
            return self._failure(LegacyOperation.INSIDE_PAYMENT, ctx, commands, exc)

    def ticket_issue(self, payload: Mapping[str, Any], ctx: LegacyContext) -> LegacyResult:
        commands: list[str] = []
        try:
            order_id = _required_text(payload, "orderId")
            order = self._get_order(order_id)
            segment_ref, traveler_ref = self._single_order_refs(order)
            segment_booking_id = self._segment_booking_id(order_id, segment_ref, traveler_ref, ctx)
            entitlement = self.client.post(
                "entitlement-ticketing",
                "/api/v1/entitlements",
                {
                    "segmentBookingId": segment_booking_id,
                    "journeyOrderId": order_id,
                    "travelerRef": traveler_ref,
                    "segmentRef": segment_ref,
                    "issuePurpose": "INITIAL",
                },
                _headers(ctx, "issue"),
            )
            commands.append("IssueEntitlement")
            entitlement_id = _required_text(entitlement, "entitlementId")
            saga_id = self._find_saga(order_id, ctx)
            if saga_id:
                self.client.post(
                    "booking-orchestration",
                    f"/api/v1/internal/booking-sagas/{quote(saga_id)}/mark-ticketed",
                    {"segmentBookingId": segment_booking_id, "entitlementId": entitlement_id},
                    _headers(ctx, "mark-ticketed"),
                )
                commands.append("MarkSegmentTicketed")
            return self._finish(
                LegacyOperation.TICKET_ISSUE,
                Outcome.SUCCEEDED,
                ctx,
                commands,
                {"entitlementId": entitlement_id, "segmentBookingId": segment_booking_id},
                None,
            )
        except Exception as exc:
            return self._failure(LegacyOperation.TICKET_ISSUE, ctx, commands, exc)

    def execute(self, payload: Mapping[str, Any], ctx: LegacyContext) -> LegacyResult:
        commands: list[str] = []
        try:
            order_id = _required_text(payload, "orderId")
            self._get_order(order_id)
            entitlement = self._first_entitlement(order_id)
            body = {
                "entitlementId": _required_text(entitlement, "entitlementId"),
                "segmentBookingId": _required_text(entitlement, "segmentBookingId"),
                "journeyOrderId": order_id,
                "travelerId": _required_text(entitlement, "travelerRef"),
                "segmentRef": _required_text(entitlement, "segmentRef"),
                "source": "GATE",
                "sourceEventId": ctx.source_ref,
                "occurredAt": rfc3339_utc(datetime.now(UTC)),
            }
            boarding = self.client.post("fulfillment", "/api/v1/fulfillment-records/boarding", body, _headers(ctx, "boarding"))
            commands.append("VerifyBoarding")
            return self._finish(
                LegacyOperation.EXECUTE,
                Outcome.SUCCEEDED,
                ctx,
                commands,
                {"fulfillmentRecordId": _required_text(boarding, "fulfillmentRecordId")},
                None,
            )
        except Exception as exc:
            return self._failure(LegacyOperation.EXECUTE, ctx, commands, exc)

    def cancel(self, payload: Mapping[str, Any], ctx: LegacyContext) -> LegacyResult:
        return self._post_sales(payload, ctx, LegacyOperation.CANCEL, "REFUND", "CUSTOMER_REQUEST", "refundableAmount", "refundAmount")

    def rebook(self, payload: Mapping[str, Any], ctx: LegacyContext) -> LegacyResult:
        return self._post_sales(payload, ctx, LegacyOperation.REBOOK, "CHANGE", "CUSTOMER_CHANGE", "amountDue", "amountDue")

    def _post_sales(
        self,
        payload: Mapping[str, Any],
        ctx: LegacyContext,
        operation: LegacyOperation,
        case_type: str,
        reason_code: str,
        evaluation_amount_field: str,
        legacy_amount_field: str,
    ) -> LegacyResult:
        commands: list[str] = []
        try:
            order_id = _required_text(payload, "orderId")
            order = self._get_order(order_id)
            entitlement = self._first_entitlement(order_id)
            scope = {
                "orderItemRefs": [_required_text(entitlement, "segmentBookingId")],
                "segmentRefs": [_required_text(entitlement, "segmentRef")],
                "travelerRefs": [_required_text(entitlement, "travelerRef")],
                "entitlementRefs": [_required_text(entitlement, "entitlementId")],
            }
            opened = self.client.post(
                "post-sales",
                "/api/v1/post-sales-cases",
                {
                    "journeyOrderId": order_id,
                    "caseType": case_type,
                    "scope": scope,
                    "reasonCode": reason_code,
                    "actorRef": _required_text(order, "accountId"),
                },
                _headers(ctx, f"{operation.value.lower()}-case"),
            )
            commands.append("OpenPostSalesCase")
            case_id = _required_text(opened, "caseId")
            evaluated = self.client.post("post-sales", f"/api/v1/post-sales-cases/{quote(case_id)}/evaluate", {}, _headers(ctx, f"{operation.value.lower()}-evaluate"))
            commands.append("EvaluatePostSalesEligibility")
            self.client.post("post-sales", f"/api/v1/post-sales-cases/{quote(case_id)}/approve", {}, _headers(ctx, f"{operation.value.lower()}-approve"))
            commands.append("ApprovePostSalesCase")
            return self._finish(
                operation,
                Outcome.SUCCEEDED,
                ctx,
                commands,
                {"caseId": case_id, legacy_amount_field: _required_mapping(evaluated, evaluation_amount_field)},
                None,
            )
        except Exception as exc:
            return self._failure(operation, ctx, commands, exc)

    def _finish(
        self,
        operation: LegacyOperation,
        outcome: Outcome,
        ctx: LegacyContext,
        commands: list[str],
        result_refs: Mapping[str, Any],
        failure_message: str | None,
    ) -> LegacyResult:
        self._publish(operation, outcome, ctx, commands, result_refs, failure_message)
        return LegacyResult(1, "success", result_refs)

    def _failure(self, operation: LegacyOperation, ctx: LegacyContext, commands: list[str], exc: Exception) -> LegacyResult:
        message = str(exc) or exc.__class__.__name__
        self._publish(operation, Outcome.FAILED, ctx, commands, {}, message)
        return LegacyResult(0, message, {})

    def _publish(
        self,
        operation: LegacyOperation,
        outcome: Outcome,
        ctx: LegacyContext,
        commands: list[str],
        result_refs: Mapping[str, Any],
        failure_message: str | None,
    ) -> None:
        event_id = deterministic_event_id(ctx.source_ref, operation.value)
        causation_id = f"cmd-{ctx.source_ref}"
        metadata = {
            "eventId": event_id,
            "occurredAt": rfc3339_utc(datetime.now(UTC)),
            "sourceCommandId": ctx.source_ref,
            "causationId": causation_id,
            "correlationId": ctx.correlation_id,
            "schemaVersion": 1,
            "attributes": {},
        }
        payload: dict[str, Any] = {
            "legacyOperation": operation.value,
            "outcome": outcome.value,
            "operatorRef": ctx.operator_ref,
            "mappedCommands": list(commands),
            "resultRefs": dict(result_refs),
            "sourceRef": ctx.source_ref,
            "metadata": metadata,
        }
        if ctx.reason:
            payload["reason"] = ctx.reason
        if failure_message:
            payload["failureMessage"] = failure_message
        self.publisher.publish(
            EventEnvelope(
                eventId=event_id,
                eventType="LegacyCommandMapped",
                occurredAt=metadata["occurredAt"],
                correlationId=ctx.correlation_id,
                causationId=causation_id,
                producer="legacy-acl",
                schemaVersion=1,
                payload=payload,
            )
        )

    def _traveler_ref(self, account_id: str, contacts_id: Any, ctx: LegacyContext, commands: list[str]) -> str:
        if isinstance(contacts_id, str) and contacts_id.strip():
            return contacts_id.strip()
        if not isinstance(contacts_id, Mapping):
            raise ValueError("contactsId is required")
        traveler = self.client.post(
            "traveler-profile",
            "/api/v1/travelers",
            {
                "accountId": account_id,
                "travelerType": "ADULT",
                "givenName": _required_text(contacts_id, "name"),
                "familyName": _required_text(contacts_id, "name"),
                "documentType": contacts_id.get("documentType", "OTHER"),
                "documentNumber": contacts_id.get("documentNumber"),
            },
            _headers(ctx, "traveler"),
        )
        commands.append("CreateTravelerProfile")
        return _required_text(traveler, "travelerId")

    def _resolve_saga(
        self,
        order_id: str,
        account_id: str,
        offer_id: str,
        traveler_refs: list[str],
        segment_refs: list[str],
        ctx: LegacyContext,
        commands: list[str],
    ) -> str:
        found = self._find_saga(order_id, ctx)
        if found:
            return found
        saga = self.client.post(
            "booking-orchestration",
            "/api/v1/internal/booking-sagas",
            {
                "journeyOrderId": order_id,
                "accountId": account_id,
                "offerId": offer_id,
                "travelerRefs": traveler_refs,
                "segmentRefs": segment_refs,
            },
            _headers(ctx, "saga"),
        )
        commands.append("StartBookingSaga")
        return _required_text(saga, "sagaId")

    def _find_saga(self, order_id: str, ctx: LegacyContext) -> str | None:
        # No list-by-order contract exists. In phase 1, the ACL starts a saga when
        # it cannot query one directly, and reconstructs refs from owner APIs for later steps.
        return None

    def _segment_booking_id(self, order_id: str, segment_ref: str, traveler_ref: str, ctx: LegacyContext) -> str:
        existing = self.client.get(
            "entitlement-ticketing",
            f"/api/v1/entitlements?journeyOrderId={quote(order_id)}&limit=20&offset=0",
            _headers(ctx, "list-entitlements"),
        )
        items = existing.get("items")
        if isinstance(items, list):
            for item in items:
                if isinstance(item, Mapping) and item.get("segmentRef") == segment_ref and item.get("travelerRef") == traveler_ref:
                    return _required_text(item, "segmentBookingId")
        return deterministic_prefixed_uuid("sb", f"{order_id}:{segment_ref}:{traveler_ref}")

    def _get_order(self, order_id: str) -> Mapping[str, Any]:
        return self.client.get("journey-order", f"/api/v1/journey-orders/{quote(order_id)}")

    def _single_order_refs(self, order: Mapping[str, Any]) -> tuple[str, str]:
        segment_refs = _required_string_list(order, "segmentRefs")
        traveler_refs = _required_string_list(order, "travelerRefs")
        return segment_refs[0], traveler_refs[0]

    def _first_entitlement(self, order_id: str) -> Mapping[str, Any]:
        page = self.client.get("entitlement-ticketing", f"/api/v1/entitlements?journeyOrderId={quote(order_id)}&limit=20&offset=0")
        item = _first_mapping(page, "items", "no entitlement found for order")
        return item


def _headers(ctx: LegacyContext, suffix: str) -> dict[str, str]:
    downstream_key = deterministic_uuid7(ctx.source_ref, suffix)
    return {"Idempotency-Key": downstream_key, "X-Correlation-Id": ctx.correlation_id, "X-Request-Id": f"{ctx.source_ref}-{suffix}"}


def _required_text(payload: Mapping[str, Any], field: str) -> str:
    value = payload.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} is required")
    return value.strip()


def _required_int(payload: Mapping[str, Any], field: str) -> int:
    value = payload.get(field)
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    raise ValueError(f"{field} is required")


def _required_mapping(payload: Mapping[str, Any], field: str) -> Mapping[str, Any]:
    value = payload.get(field)
    if isinstance(value, Mapping):
        return value
    raise ValueError(f"{field} is required")


def _required_string_list(payload: Mapping[str, Any], field: str) -> list[str]:
    value = payload.get(field)
    if isinstance(value, list):
        result = [item.strip() for item in value if isinstance(item, str) and item.strip()]
        if result:
            return result
    raise ValueError(f"{field} is required")


def _first_mapping(payload: Mapping[str, Any], field: str, message: str) -> Mapping[str, Any]:
    value = payload.get(field)
    if isinstance(value, list) and value and isinstance(value[0], Mapping):
        return value[0]
    raise DownstreamError(message)


def _nested(payload: Mapping[str, Any], outer: str, inner: str) -> Any:
    value = payload.get(outer)
    return value.get(inner) if isinstance(value, Mapping) else None
