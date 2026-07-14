from __future__ import annotations

import time
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


# offer-management resolves offers against upstream projections it consumes
# asynchronously (itinerary, fare quote, traveler snapshot). The legacy facade
# is synchronous, so it owns the wait: retry these codes with bounded backoff
# until the projections catch up.
_PROJECTION_LAG_CODES = frozenset({
    "MISSING_ITINERARY_SNAPSHOT",
    "MISSING_FARE_QUOTE",
    "MISSING_TRAVELER_SNAPSHOT",
})
_PROJECTION_RETRY_DELAYS_SECONDS = (0.2, 0.4, 0.8, 1.6, 3.2)
_DEFAULT_PAYMENT_CHANNEL = "ALIPAY_SIM"


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

            offer = self._post_awaiting_projections(
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
            self._capture_payment(payment_intent_id, payload, ctx, "capture")
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
        commands: list[str] = []
        result_refs: dict[str, Any] = {"rebookedLegs": []}
        try:
            order_id = _required_text(payload, "orderId")
            order = self._get_order(order_id)
            entitlements = _ordered_entitlements(order, self._entitlements_for_order(order_id, ctx))
            scope = _post_sales_scope(entitlements)
            opened = self.client.post(
                "post-sales",
                "/api/v1/post-sales-cases",
                {
                    "journeyOrderId": order_id,
                    "caseType": "CHANGE",
                    "scope": scope,
                    "reasonCode": "CUSTOMER_CHANGE",
                    "actorRef": _required_text(order, "accountId"),
                },
                _headers(ctx, "rebook-case"),
            )
            commands.append("OpenPostSalesCase")
            case_id = _required_text(opened, "caseId")
            evaluated = self.client.post("post-sales", f"/api/v1/post-sales-cases/{quote(case_id)}/evaluate", {}, _headers(ctx, "rebook-evaluate"))
            commands.append("EvaluatePostSalesEligibility")
            self.client.post("post-sales", f"/api/v1/post-sales-cases/{quote(case_id)}/approve", {}, _headers(ctx, "rebook-approve"))
            commands.append("ApprovePostSalesCase")
            amount_due = _required_mapping(evaluated, "amountDue")
            result_refs = {"caseId": case_id, "amountDue": amount_due, "rebookedLegs": []}

            for index in range(_replacement_count(payload, len(entitlements))):
                entitlement = entitlements[index] if index < len(entitlements) else entitlements[-1]
                leg_result = self._rebook_leg(payload, order, entitlement, index, ctx, commands)
                result_refs["rebookedLegs"].append(leg_result)

            return self._finish(LegacyOperation.REBOOK, Outcome.SUCCEEDED, ctx, commands, result_refs, None)
        except Exception as exc:
            if result_refs.get("caseId") and result_refs.get("rebookedLegs"):
                completed = len(result_refs["rebookedLegs"]) if isinstance(result_refs.get("rebookedLegs"), list) else 0
                message = f"rebook failed after {completed} replacement leg(s): {str(exc) or exc.__class__.__name__}"
                partial_refs = {**result_refs, "partial": True, "failureMessage": message}
                self._publish(LegacyOperation.REBOOK, Outcome.FAILED, ctx, commands, partial_refs, message)
                return LegacyResult(0, message, partial_refs)
            return self._failure(LegacyOperation.REBOOK, ctx, commands, exc)

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

    def _rebook_leg(
        self,
        payload: Mapping[str, Any],
        order: Mapping[str, Any],
        entitlement: Mapping[str, Any],
        index: int,
        ctx: LegacyContext,
        commands: list[str],
    ) -> dict[str, Any]:
        account_id = _required_text(order, "accountId")
        traveler_ref = _required_text(entitlement, "travelerRef")
        replacement = _replacement_leg(payload, index)
        itinerary_ref, segment_ref = self._replacement_refs(payload, replacement, order, entitlement, index, traveler_ref, ctx, commands)

        suffix = f"rebook-{index + 1}"
        fare_quote = self.client.post(
            "fare-pricing",
            "/api/v1/fare-quotes",
            {"travelerRefs": [traveler_ref], "channel": "WEB", "segmentRefs": [segment_ref]},
            _headers(ctx, f"{suffix}-fare-quote"),
        )
        commands.append("CreateFareQuote")
        offer = self._post_awaiting_projections(
            "offer-management",
            "/api/v1/offers",
            {
                "accountId": account_id,
                "channelId": "WEB",
                "itineraryRef": itinerary_ref,
                "travelerRefs": [traveler_ref],
                "quoteRequestId": str(fare_quote.get("quoteId", ctx.source_ref)),
            },
            _headers(ctx, f"{suffix}-offer"),
        )
        commands.append("CreateOffer")
        offer_id = _required_text(offer, "offerId")
        offer_version = _required_int(offer, "offerVersion")
        replacement_order = self.client.post(
            "journey-order",
            "/api/v1/journey-orders",
            {
                "accountId": account_id,
                "offerId": offer_id,
                "offerVersion": offer_version,
                "travelerRefs": [traveler_ref],
                "segmentRefs": [segment_ref],
            },
            _headers(ctx, f"{suffix}-order"),
        )
        commands.append("CreateJourneyOrder")
        replacement_order_id = _required_text(replacement_order, "orderId")
        intent = self.client.post(
            "payment",
            "/api/v1/payment-intents",
            {
                "businessRef": replacement_order_id,
                "purpose": "rebook",
                "amount": _payment_amount(offer, fare_quote, replacement),
                "payerRef": account_id,
            },
            _headers(ctx, f"{suffix}-payment-intent"),
        )
        commands.append("CreatePaymentIntent")
        payment_intent_id = _required_text(intent, "paymentIntentId")
        self._capture_payment(payment_intent_id, payload, ctx, f"{suffix}-capture")
        commands.append("CapturePayment")
        return {
            "originalSegmentRef": _required_text(entitlement, "segmentRef"),
            "replacementSegmentRef": segment_ref,
            "replacementOrderId": replacement_order_id,
            "offerId": offer_id,
            "paymentIntentId": payment_intent_id,
        }

    def _replacement_refs(
        self,
        payload: Mapping[str, Any],
        replacement: Mapping[str, Any],
        order: Mapping[str, Any],
        entitlement: Mapping[str, Any],
        index: int,
        traveler_ref: str,
        ctx: LegacyContext,
        commands: list[str],
    ) -> tuple[str, str]:
        itinerary_ref = (
            _optional_text(replacement, "itineraryRef")
            or _optional_indexed_text(payload, "itineraryRefs", index)
            or _optional_text(payload, "itineraryRef")
        )
        segment_ref = (
            _optional_text(replacement, "segmentRef")
            or _optional_text(replacement, "replacementSegmentRef")
            or _optional_indexed_text(payload, "replacementSegmentRefs", index)
            or _optional_indexed_text(payload, "segmentRefs", index)
        )
        if itinerary_ref and segment_ref:
            return itinerary_ref, segment_ref

        origin = (
            _optional_text(replacement, "from")
            or _optional_text(replacement, "originRef")
            or _optional_text(payload, "from")
            or _optional_text(payload, "originRef")
        )
        destination = (
            _optional_text(replacement, "to")
            or _optional_text(replacement, "destinationRef")
            or _optional_text(payload, "to")
            or _optional_text(payload, "destinationRef")
        )
        departure_date = _optional_text(replacement, "date") or _optional_text(payload, "date")
        if origin and destination and departure_date:
            search = self.client.post(
                "trip-planning",
                "/api/v1/itineraries/search",
                {
                    "originRef": origin,
                    "destinationRef": destination,
                    "departureDate": departure_date,
                    "travelerRefs": [traveler_ref],
                    "channel": "WEB",
                },
                _headers(ctx, f"rebook-{index + 1}-search"),
            )
            commands.append("SearchItineraries")
            itinerary = _first_mapping(search, "itineraries", "no replacement itinerary found")
            leg = _indexed_mapping(itinerary, "legs", index) or _first_mapping(itinerary, "legs", "replacement itinerary contains no legs")
            return _required_text(itinerary, "itineraryRef"), _required_text(leg, "serviceSegmentRef")

        # Compatibility fallback for legacy callers that only supplied orderId/date/seatType.
        # Reuse the original offer itinerary snapshot, so the normal quote -> offer ->
        # order -> payment chain still has a projected itinerary to resolve against.
        original_segment = _required_text(entitlement, "segmentRef")
        original_offer_id = _optional_text(order, "offerId")
        if original_offer_id:
            original_offer = self.client.get("offer-management", f"/api/v1/offers/{quote(original_offer_id)}", _headers(ctx, f"rebook-{index + 1}-original-offer"))
            original_itinerary_ref = _optional_text(original_offer, "itineraryRef")
            if original_itinerary_ref:
                return original_itinerary_ref, original_segment
        return f"legacy-rebook:{original_segment}", original_segment

    def _capture_payment(self, payment_intent_id: str, payload: Mapping[str, Any], ctx: LegacyContext, suffix: str) -> None:
        self.client.post(
            "payment",
            f"/api/v1/payment-intents/{quote(payment_intent_id)}/capture",
            {"channelRef": _channel_ref(payload)},
            _headers(ctx, suffix),
        )

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

    def _post_awaiting_projections(
        self,
        service: str,
        path: str,
        body: Mapping[str, Any],
        headers: Mapping[str, str],
    ) -> dict[str, Any]:
        # Idempotency-Key in headers is deterministic, so retries are safe.
        for delay in _PROJECTION_RETRY_DELAYS_SECONDS:
            try:
                return self.client.post(service, path, body, headers)
            except DownstreamError as exc:
                if exc.code not in _PROJECTION_LAG_CODES:
                    raise
                time.sleep(delay)
        return self.client.post(service, path, body, headers)

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
        return self._entitlements_for_order(order_id, None)[0]

    def _entitlements_for_order(self, order_id: str, ctx: LegacyContext | None) -> list[Mapping[str, Any]]:
        headers = _headers(ctx, "list-entitlements") if ctx else None
        page = self.client.get("entitlement-ticketing", f"/api/v1/entitlements?journeyOrderId={quote(order_id)}&limit=100&offset=0", headers)
        items = page.get("items")
        if isinstance(items, list):
            entitlements = [item for item in items if isinstance(item, Mapping)]
            if entitlements:
                return entitlements
        raise DownstreamError("no entitlement found for order")


def _ordered_entitlements(order: Mapping[str, Any], entitlements: list[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    segment_order = {segment_ref: index for index, segment_ref in enumerate(_required_string_list(order, "segmentRefs"))}
    return sorted(entitlements, key=lambda entitlement: segment_order.get(str(entitlement.get("segmentRef", "")), len(segment_order)))


def _post_sales_scope(entitlements: list[Mapping[str, Any]]) -> dict[str, list[str]]:
    return {
        "orderItemRefs": [_required_text(entitlement, "segmentBookingId") for entitlement in entitlements],
        "segmentRefs": [_required_text(entitlement, "segmentRef") for entitlement in entitlements],
        "travelerRefs": [_required_text(entitlement, "travelerRef") for entitlement in entitlements],
        "entitlementRefs": [_required_text(entitlement, "entitlementId") for entitlement in entitlements],
    }


def _replacement_leg(payload: Mapping[str, Any], index: int) -> Mapping[str, Any]:
    for field in ("replacementLegs", "rebookLegs", "legs", "replacementItineraries"):
        value = payload.get(field)
        if isinstance(value, list) and index < len(value) and isinstance(value[index], Mapping):
            return value[index]
    return {}


def _replacement_count(payload: Mapping[str, Any], entitlement_count: int) -> int:
    count = entitlement_count
    for field in ("replacementLegs", "rebookLegs", "legs", "replacementItineraries", "itineraryRefs", "replacementSegmentRefs", "segmentRefs"):
        value = payload.get(field)
        if isinstance(value, list):
            count = max(count, len(value))
    return count


def _payment_amount(offer: Mapping[str, Any], fare_quote: Mapping[str, Any], replacement: Mapping[str, Any]) -> Mapping[str, Any]:
    for candidate in (replacement.get("price"), replacement.get("amount"), offer.get("total"), _nested(fare_quote, "breakdown", "total")):
        if isinstance(candidate, Mapping) and _positive_minor_units(candidate):
            return candidate
    raise ValueError("replacement payment amount is required")


def _positive_minor_units(amount: Mapping[str, Any]) -> bool:
    value = amount.get("minorUnits")
    return isinstance(value, int) and not isinstance(value, bool) and value > 0 and isinstance(amount.get("currency"), str) and bool(amount.get("currency"))


def _channel_ref(payload: Mapping[str, Any]) -> Mapping[str, str]:
    supplied = payload.get("channelRef")
    channel_ref: dict[str, str] = {}
    if isinstance(supplied, Mapping):
        for field in ("channel", "channelOrderId", "faultSeedRef"):
            value = supplied.get(field)
            if isinstance(value, str) and value.strip():
                channel_ref[field] = value.strip()
    channel = channel_ref.get("channel", _DEFAULT_PAYMENT_CHANNEL)
    if channel not in {"ALIPAY_SIM", "WECHAT_SIM", "UNIONPAY_SIM"}:
        raise ValueError("channelRef.channel is unsupported")
    channel_ref["channel"] = channel
    return channel_ref


def _optional_text(payload: Mapping[str, Any], field: str) -> str | None:
    value = payload.get(field)
    if isinstance(value, str) and value.strip():
        return value.strip()
    return None


def _optional_indexed_text(payload: Mapping[str, Any], field: str, index: int) -> str | None:
    value = payload.get(field)
    if isinstance(value, list) and index < len(value):
        item = value[index]
        if isinstance(item, str) and item.strip():
            return item.strip()
    return None


def _indexed_mapping(payload: Mapping[str, Any], field: str, index: int) -> Mapping[str, Any] | None:
    value = payload.get(field)
    if isinstance(value, list) and index < len(value) and isinstance(value[index], Mapping):
        return value[index]
    return None


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
