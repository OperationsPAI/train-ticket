from __future__ import annotations

from datetime import UTC, datetime
from decimal import Decimal
import uuid
from typing import Any

from fastapi import APIRouter, Header, Request

from fare_pricing.application import DomainEventService
from fare_pricing.ids import prefixed_uuid7
from fare_pricing.application.idempotency import IdempotencyRecord, request_fingerprint
from fare_pricing.application.service import (
    FarePricingService,
    QuoteNotFoundError,
    RuleSetNotFoundError,
)
from fare_pricing.domain import (
    AdjustmentQuote,
    AssessmentPurpose,
    FareBreakdown,
    FareQuote,
    Money,
    PriceComponent,
    PricingError,
    QuoteStatus,
    RuleSnapshot,
)

from fare_pricing.ports.messaging import PublishFailed

from .errors import ApiError
from .schemas import AdjustmentQuoteRequest, FareQuoteRequest

router = APIRouter(prefix="/api/v1", tags=["fare-pricing"])


def _money_to_schema(m: Money) -> dict[str, Any]:
    minor_units = int(m.amount * Decimal("100"))
    return {"currency": m.currency, "minorUnits": minor_units}


def _explanation_to_schema(explanation: Any) -> dict[str, Any]:
    return {"code": explanation.code, "parameters": dict(explanation.as_mapping())}


def _component_to_schema(c: PriceComponent) -> dict[str, Any]:
    return {
        "ruleId": c.rule_id,
        "amount": _money_to_schema(c.amount),
        "explanation": _explanation_to_schema(c.explanation),
        "refundable": c.refundable,
    }


def _breakdown_to_schema(bd: FareBreakdown) -> dict[str, Any]:
    return {
        "baseFare": _money_to_schema(bd.base_fare),
        "taxes": [_component_to_schema(t) for t in bd.taxes],
        "fees": [_component_to_schema(f) for f in bd.fees],
        "discounts": [_component_to_schema(d) for d in bd.discounts],
        "total": _money_to_schema(bd.total),
    }


def _snapshot_to_schema(snapshot: RuleSnapshot | None) -> dict[str, Any] | None:
    if snapshot is None:
        return None
    return {
        "ruleSetId": snapshot.rule_set_id,
        "ruleSetVersion": snapshot.rule_set_version,
        "capturedAt": _timestamp_str(snapshot.captured_at),
        "ruleIds": list(snapshot.rule_ids),
        "explanationCodes": list(snapshot.explanation_codes),
        "digest": snapshot.digest,
    }


def _timestamp_str(dt: datetime) -> str:
    utc_dt = dt.astimezone(UTC) if dt.tzinfo is not None else dt.replace(tzinfo=UTC)
    return utc_dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{utc_dt.microsecond // 1000:03d}Z"


def _validation_error(message: str) -> ApiError:
    return ApiError("VALIDATION_FAILED", message, 400)


def _domain_error(exc: Exception) -> ApiError:
    return ApiError("DOMAIN_RULE_VIOLATION", str(exc), 422)


def require_uuid7(key: str | None) -> str:
    try:
        parsed = uuid.UUID(key or "")
        if parsed.version != 7:
            raise ValueError
    except (ValueError, AttributeError, TypeError):
        raise ApiError("VALIDATION_FAILED", "Idempotency-Key must be a UUID v7", 400)
    return str(parsed)


def _check_idempotency(request: Request, scope: str, key: str, body: dict[str, Any]) -> dict[str, Any] | None:
    store = request.app.state.idempotency_store
    fingerprint = request_fingerprint(body)
    record = store.get(scope, key)
    if record is None:
        return None
    if record.fingerprint != fingerprint:
        raise ApiError("IDEMPOTENCY_KEY_REUSED", "Idempotency-Key reused with a different request body", 422)
    return dict(record.response_body)


def _correlation_id(request: Request) -> str:
    return str(getattr(request.state, "correlation_id", "") or prefixed_uuid7("corr"))


def _command_id() -> str:
    return prefixed_uuid7("cmd")


def _publish_event(request: Request, event_type: str, causation_id: str, payload: dict[str, Any]) -> None:
    event_service: DomainEventService = request.app.state.domain_event_service
    try:
        event_service.publish_event(
            event_type=event_type,
            causation_id=causation_id,
            correlation_id=_correlation_id(request),
            payload=payload,
            occurred_at=datetime.now(UTC),
        )
    except PublishFailed as exc:
        raise ApiError("UNAVAILABLE", "Event bus publish failed", 503) from exc


def _store_idempotency(request: Request, scope: str, key: str, body: dict[str, Any], response: dict[str, Any]) -> None:
    request.app.state.idempotency_store.put(
        scope,
        key,
        IdempotencyRecord(request_fingerprint(body), 201, dict(response)),
    )


@router.post("/fare-quotes", status_code=201)
def compute_fare_quote(
    request: Request,
    req: FareQuoteRequest,
    idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
) -> dict[str, Any]:
    key = require_uuid7(idempotency_key)
    body_dict = req.model_dump()
    cached = _check_idempotency(request, "POST /api/v1/fare-quotes", key, body_dict)
    if cached is not None:
        return cached

    service: FarePricingService = request.app.state.fare_pricing_service
    rule_set_id = service.find_published_rule_set_id(req.channel)
    if rule_set_id is None:
        raise _validation_error("No applicable fare rule set found")

    causation_id = _command_id()
    try:
        quote = service.compute_fare_quote(
            quote_id=prefixed_uuid7("fq"),
            input_hash=request_fingerprint(body_dict),
            traveler_refs=req.travelerRefs,
            channel=req.channel,
            rule_set_id=rule_set_id,
            requested_currency="CNY",
            segment_refs=req.segmentRefs,
        )
    except (PricingError, RuleSetNotFoundError) as exc:
        raise _domain_error(exc) from exc

    resp = _fare_quote_to_response(quote)
    _publish_event(request, "FareQuoteComputed", causation_id, _fare_quote_event_payload(quote))
    _store_idempotency(request, "POST /api/v1/fare-quotes", key, body_dict, resp)
    return resp


@router.get("/fare-quotes/{quote_id}")
def get_fare_quote(request: Request, quote_id: str) -> dict[str, Any]:
    service: FarePricingService = request.app.state.fare_pricing_service
    try:
        quote = service.get_fare_quote(quote_id)
    except QuoteNotFoundError as exc:
        raise ApiError("NOT_FOUND", f"Fare quote not found: {quote_id}", 404) from exc
    return _fare_quote_to_response(quote)


@router.post("/adjustment-quotes", status_code=201)
def compute_adjustment_quote(
    request: Request,
    req: AdjustmentQuoteRequest,
    idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
) -> dict[str, Any]:
    key = require_uuid7(idempotency_key)
    body_dict = req.model_dump()
    cached = _check_idempotency(request, "POST /api/v1/adjustment-quotes", key, body_dict)
    if cached is not None:
        return cached

    service: FarePricingService = request.app.state.fare_pricing_service
    purpose = AssessmentPurpose.REFUND if req.purpose == "REFUND" else AssessmentPurpose.CHANGE
    original_quote_id = service.find_fare_quote_id_for_segments(req.segmentRefs)
    if original_quote_id is None:
        raise ApiError("PRECONDITION_FAILED", "Original fare quote not found for requested segments", 412)
    original_quote = service.get_fare_quote(original_quote_id)
    rule_set_id = service.find_published_rule_set_id(original_quote.channel)
    if rule_set_id is None:
        raise _validation_error("No applicable fare rule set found")

    causation_id = _command_id()
    try:
        aq = service.compute_adjustment_quote(
            assessment_id=prefixed_uuid7("fa"),
            adjustment_quote_id=prefixed_uuid7("aq"),
            purpose=purpose,
            original_quote_id=original_quote_id,
            rule_set_id=rule_set_id,
            target_quote_id=original_quote_id if purpose == AssessmentPurpose.CHANGE else None,
        )
    except (PricingError, QuoteNotFoundError, RuleSetNotFoundError) as exc:
        raise _domain_error(exc) from exc

    resp = _adjustment_quote_to_response(aq)
    _publish_event(request, "AdjustmentQuoteComputed", causation_id, _adjustment_quote_event_payload(aq, req))
    _store_idempotency(request, "POST /api/v1/adjustment-quotes", key, body_dict, resp)
    return resp


def _fare_quote_to_response(quote: FareQuote) -> dict[str, Any]:
    resp: dict[str, Any] = {
        "quoteId": quote.quote_id,
        "status": quote.status.value.upper(),
        "validFrom": _timestamp_str(quote.valid_from),
        "validUntil": _timestamp_str(quote.valid_until),
    }
    if quote.status == QuoteStatus.FAILED:
        resp["failedReason"] = quote.failed_reason
    else:
        if quote.breakdown is not None:
            resp["breakdown"] = _breakdown_to_schema(quote.breakdown)
        if quote.rule_snapshot is not None:
            resp["ruleSnapshot"] = _snapshot_to_schema(quote.rule_snapshot)
    return resp


def _adjustment_quote_to_response(aq: AdjustmentQuote) -> dict[str, Any]:
    status = "FAILED" if aq.status == QuoteStatus.FAILED else "QUOTED"
    resp = {
        "adjustmentQuoteId": aq.adjustment_quote_id,
        "purpose": aq.purpose.value.upper(),
        "status": status,
        "refundableAmount": _money_to_schema(aq.refundable_amount),
        "amountDue": _money_to_schema(aq.amount_due),
        "validUntil": _timestamp_str(aq.valid_until),
    }
    if aq.failed_reason is not None:
        resp["failedReason"] = aq.failed_reason
    return resp


def _fare_quote_event_payload(quote: FareQuote) -> dict[str, Any]:
    payload = _fare_quote_to_response(quote)
    payload["inputHash"] = quote.input_hash
    payload["travelerRefs"] = list(quote.traveler_refs)
    payload["channel"] = quote.channel
    payload["currency"] = quote.currency
    return payload


def _adjustment_quote_event_payload(aq: AdjustmentQuote, request_body: AdjustmentQuoteRequest | None = None) -> dict[str, Any]:
    payload = _adjustment_quote_to_response(aq)
    payload["originalQuoteId"] = aq.original_quote_id
    if aq.target_quote_id is not None:
        payload["targetQuoteId"] = aq.target_quote_id
    if request_body is not None:
        payload["journeyOrderId"] = request_body.journeyOrderId
        payload["entitlementIds"] = list(request_body.entitlementIds)
        payload["segmentRefs"] = list(request_body.segmentRefs)
    return payload
