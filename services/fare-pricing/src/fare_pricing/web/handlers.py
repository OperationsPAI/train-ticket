from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from typing import Any
from uuid import uuid4

from fastapi import APIRouter, Header, HTTPException, Request

from fare_pricing.domain import (
    AdjustmentQuote,
    AssessmentPurpose,
    FareBreakdown,
    FareQuote,
    Money,
    PriceComponent,
    PricingError,
    QuoteStatus,
    RuleSetStatus,
    RuleSnapshot,
)
from fare_pricing.application.service import (
    FarePricingService,
    QuoteNotFoundError,
    AdjustmentQuoteNotFoundError,
    RuleSetNotFoundError,
)

from .schemas import (
    AdjustmentQuoteRequest,
    FareQuoteRequest,
)

router = APIRouter(prefix="/api/v1", tags=["fare-pricing"])


def _money_to_schema(m: Money) -> dict[str, Any]:
    """Convert domain Money to {currency, minorUnits}."""
    minor_units = int(m.amount * Decimal("100"))
    return {
        "currency": m.currency,
        "minorUnits": minor_units,
    }


def _explanation_to_schema(explanation: Any) -> dict[str, Any]:
    return {
        "code": explanation.code,
        "parameters": dict(explanation.as_mapping()),
    }


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
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{dt.microsecond // 1000:03d}Z"


def _make_error(code: str, message: str, correlation_id: str, status_code: int = 400) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={
            "code": code,
            "message": message,
            "correlationId": correlation_id,
            "details": {},
        },
    )


# --- Idempotency store ---

_idempotency_store: dict[str, dict[str, Any]] = {}


def _check_idempotency(key: str | None, request_body: dict[str, Any], correlation_id: str) -> dict[str, Any] | None:
    """If idempotency key is provided, check for replay."""
    if key is None:
        return None
    if key in _idempotency_store:
        existing = _idempotency_store[key]
        if existing.get("body") != request_body:
            raise _make_error(
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key reused with a different request body",
                correlation_id,
                422,
            )
        return existing.get("response")
    return None


def _store_idempotency(key: str | None, body: dict[str, Any], response: dict[str, Any]) -> None:
    if key is not None:
        _idempotency_store[key] = {"body": body, "response": response}


@router.post("/fare-quotes", status_code=201)
def compute_fare_quote(
    request: Request,
    req: FareQuoteRequest,
    idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    x_correlation_id: str | None = Header(None, alias="X-Correlation-Id"),
) -> dict[str, Any]:
    correlation_id = x_correlation_id or str(uuid4())
    body_dict = req.model_dump()

    # Idempotency check
    cached = _check_idempotency(idempotency_key, body_dict, correlation_id)
    if cached is not None:
        return cached

    service: FarePricingService = request.app.state.fare_pricing_service

    rule_set_id = _find_rule_set_id(service, req.channel)
    if rule_set_id is None:
        raise _make_error("VALIDATION_FAILED", "No applicable fare rule set found", correlation_id, 400)

    quote_id = f"fq-{uuid4()}"
    input_hash = str(uuid4())

    try:
        quote = service.compute_fare_quote(
            quote_id=quote_id,
            input_hash=input_hash,
            traveler_refs=req.travelerRefs,
            channel=req.channel,
            rule_set_id=rule_set_id,
            requested_currency="CNY",
        )
    except (PricingError, RuleSetNotFoundError) as e:
        raise _make_error("DOMAIN_RULE_VIOLATION", str(e), correlation_id, 422)

    resp = _fare_quote_to_response(quote)
    _store_idempotency(idempotency_key, body_dict, resp)
    return resp


@router.get("/fare-quotes/{quote_id}")
def get_fare_quote(
    request: Request,
    quote_id: str,
    x_correlation_id: str | None = Header(None, alias="X-Correlation-Id"),
) -> dict[str, Any]:
    correlation_id = x_correlation_id or str(uuid4())
    service: FarePricingService = request.app.state.fare_pricing_service
    try:
        quote = service.get_fare_quote(quote_id)
    except QuoteNotFoundError:
        raise _make_error("NOT_FOUND", f"Fare quote not found: {quote_id}", correlation_id, 404)
    return _fare_quote_to_response(quote)


@router.post("/adjustment-quotes", status_code=201)
def compute_adjustment_quote(
    request: Request,
    req: AdjustmentQuoteRequest,
    idempotency_key: str | None = Header(None, alias="Idempotency-Key"),
    x_correlation_id: str | None = Header(None, alias="X-Correlation-Id"),
) -> dict[str, Any]:
    correlation_id = x_correlation_id or str(uuid4())
    body_dict = req.model_dump()

    cached = _check_idempotency(idempotency_key, body_dict, correlation_id)
    if cached is not None:
        return cached

    service: FarePricingService = request.app.state.fare_pricing_service

    purpose = AssessmentPurpose.REFUND if req.purpose == "REFUND" else AssessmentPurpose.CHANGE

    rule_set_id = _find_rule_set_id(service, "web")
    if rule_set_id is None:
        raise _make_error("VALIDATION_FAILED", "No applicable fare rule set found", correlation_id, 400)

    if not service._store.fare_quotes:
        raise _make_error("NOT_FOUND", "No original fare quote found for adjustment", correlation_id, 404)

    original_quote_id = list(service._store.fare_quotes.keys())[0]
    assessment_id = f"fa-{uuid4()}"
    adjustment_quote_id = f"aq-{uuid4()}"

    try:
        aq = service.compute_adjustment_quote(
            assessment_id=assessment_id,
            adjustment_quote_id=adjustment_quote_id,
            purpose=purpose,
            original_quote_id=original_quote_id,
            rule_set_id=rule_set_id,
        )
    except (PricingError, QuoteNotFoundError, RuleSetNotFoundError) as e:
        raise _make_error("DOMAIN_RULE_VIOLATION", str(e), correlation_id, 422)

    resp = _adjustment_quote_to_response(aq)
    _store_idempotency(idempotency_key, body_dict, resp)
    return resp


def _find_rule_set_id(service: FarePricingService, channel: str) -> str | None:
    """Find the first published rule set for the given channel."""
    for rs_id, rs in service._store.fare_rule_sets.items():
        if rs.status == RuleSetStatus.PUBLISHED and rs.channel == channel:
            return rs_id
    return None


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
    return {
        "adjustmentQuoteId": aq.adjustment_quote_id,
        "purpose": aq.purpose.value.upper(),
        "status": aq.status.value.upper(),
        "refundableAmount": _money_to_schema(aq.refundable_amount),
        "amountDue": _money_to_schema(aq.amount_due),
        "validUntil": _timestamp_str(aq.valid_until),
        "failedReason": aq.failed_reason,
    }
