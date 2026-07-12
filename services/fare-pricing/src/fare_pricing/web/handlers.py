from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from fastapi import APIRouter, Request

from fare_pricing.application import deterministic_rule_set_event_id
from fare_pricing.ports import EventEnvelope
from train_ticket_platform.messaging import PublishFailed
from train_ticket_platform.storage import OptimisticConcurrencyError
from fare_pricing.ids import prefixed_uuid7
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
    FareRule,
    FareRuleSet,
    Money,
    PriceComponent,
    PriceExplanation,
    PricingError,
    QuoteStatus,
    RuleKind,
    RuleSnapshot,
    ValidityWindow,
)

from .errors import ApiError
from .schemas import AdjustmentQuoteRequest, CreateFareRuleSetRequest, FareQuoteRequest

router = APIRouter(prefix="/api/v1", tags=["fare-pricing"])


def _money_to_schema(m: Money) -> dict[str, Any]:
    return {"currency": m.currency, "minorUnits": m.amount_minor}


def _explanation_to_schema(explanation: PriceExplanation) -> dict[str, Any]:
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
        "dynamicAdjustments": [_component_to_schema(d) for d in bd.dynamic_adjustments],
        "total": _money_to_schema(bd.total),
        **dict(bd.pricing_components),
    }


def _rule_to_schema(rule: FareRule) -> dict[str, Any]:
    return {
        "ruleId": rule.rule_id,
        "kind": rule.kind.value,
        "amount": _money_to_schema(rule.amount),
        "explanation": _explanation_to_schema(rule.explanation),
        "refundable": rule.refundable,
    }


def _rule_set_to_response(rule_set: FareRuleSet) -> dict[str, Any]:
    return {
        "ruleSetId": rule_set.rule_set_id,
        "supplierId": rule_set.supplier_id,
        "contractId": rule_set.contract_id,
        "productCode": rule_set.product_code,
        "mode": rule_set.mode,
        "channel": rule_set.channel,
        "version": rule_set.version,
        "status": rule_set.status.value.upper(),
        "effectiveWindow": {
            "startsAt": _timestamp_str(rule_set.effective_window.starts_at),
            "endsAt": _timestamp_str(rule_set.effective_window.ends_at),
        },
        "publishedAt": _timestamp_str(rule_set.published_at) if rule_set.published_at is not None else None,
        "rules": [_rule_to_schema(rule) for rule in rule_set.rules],
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


def _conflict_error(exc: Exception) -> ApiError:
    return ApiError("CONFLICT", str(exc), 409)


def _correlation_id(request: Request) -> str:
    return str(getattr(request.state, "correlation_id", "") or prefixed_uuid7("corr"))


def _contract_input_hash(segment_refs: list[str], channel: str, traveler_refs: list[str]) -> str:
    """Normative inputHash per events/fare-pricing.md: sha256 of
    sorted(segmentRefs)|channel|sorted(travelerRefs)."""
    import hashlib

    material = ",".join(sorted(segment_refs)) + "|" + channel + "|" + ",".join(sorted(traveler_refs))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def _command_id() -> str:
    return prefixed_uuid7("cmd")


def _event_envelope(request: Request, event_type: str, causation_id: str, payload: dict[str, Any], event_id: str | None = None) -> EventEnvelope:
    return EventEnvelope(
        event_id=event_id or prefixed_uuid7("evt"),
        event_type=event_type,
        occurred_at=datetime.now(UTC),
        causation_id=causation_id,
        correlation_id=_correlation_id(request),
        payload=payload,
    )



def _effective_datetime(value: datetime) -> datetime:
    return value.astimezone(UTC) if value.tzinfo is not None else value.replace(tzinfo=UTC)


def _rule_set_from_request(rule_set_id: str, req: CreateFareRuleSetRequest) -> FareRuleSet:
    rules = tuple(
        FareRule(
            rule_id=rule.ruleId,
            kind=RuleKind(rule.kind),
            amount=Money.from_minor(rule.amount.minorUnits, rule.amount.currency),
            explanation=PriceExplanation(rule.explanation.code, rule.explanation.parameters),
            refundable=rule.refundable,
        )
        for rule in req.rules
    )
    return FareRuleSet(
        rule_set_id=rule_set_id,
        supplier_id=req.supplierId,
        product_code=req.productCode,
        mode=req.mode,
        channel=req.channel,
        version=req.version,
        effective_window=ValidityWindow(
            _effective_datetime(req.effectiveWindow.startsAt),
            _effective_datetime(req.effectiveWindow.endsAt),
        ),
        rules=rules,
        contract_id=req.contractId,
    )


@router.post("/fare-rule-sets", status_code=201)
def create_fare_rule_set(request: Request, req: CreateFareRuleSetRequest) -> dict[str, Any]:
    service: FarePricingService = request.app.state.fare_pricing_service
    try:
        rule_set = service.create_rule_set(_rule_set_from_request(prefixed_uuid7("frs"), req))
    except PricingError as exc:
        raise _domain_error(exc) from exc
    return _rule_set_to_response(rule_set)


@router.post("/fare-rule-sets/{rule_set_id}/publish", status_code=200)
def publish_fare_rule_set(request: Request, rule_set_id: str) -> dict[str, Any]:
    service: FarePricingService = request.app.state.fare_pricing_service
    causation_id = _command_id()
    try:
        with service.transaction():
            published, superseded, newly_published, originals = service.publish_rule_set(rule_set_id)
            if newly_published:
                envelopes = [
                    _event_envelope(
                        request,
                        "FareRuleSetPublished",
                        causation_id,
                        _fare_rule_set_published_payload(published),
                        deterministic_rule_set_event_id(published.rule_set_id, published.version, "published"),
                    )
                ]
                envelopes.extend(
                    _event_envelope(
                        request,
                        "FareRuleSetSuperseded",
                        causation_id,
                        _fare_rule_set_superseded_payload(old_rule_set, published),
                        deterministic_rule_set_event_id(old_rule_set.rule_set_id, old_rule_set.version, "superseded"),
                    )
                    for old_rule_set in superseded
                )
                service.append_outbox(tuple(envelopes))
                if getattr(request.app.state, "publish_events_synchronously", True):
                    try:
                        for envelope in envelopes:
                            request.app.state.domain_event_service._publisher.publish(envelope)
                    except PublishFailed as exc:
                        service.restore_rule_sets(originals)
                        raise ApiError("UNAVAILABLE", "Event bus publish failed", 503) from exc
        return _rule_set_to_response(published)
    except RuleSetNotFoundError as exc:
        raise ApiError("NOT_FOUND", f"Fare rule set not found: {rule_set_id}", 404) from exc
    except OptimisticConcurrencyError as exc:
        raise _conflict_error(exc) from exc
    except PricingError as exc:
        raise _domain_error(exc) from exc


@router.post("/fare-quotes", status_code=201)
def compute_fare_quote(
    request: Request,
    req: FareQuoteRequest,
) -> dict[str, Any]:
    service: FarePricingService = request.app.state.fare_pricing_service
    rule_set_id = service.find_published_rule_set_id(req.channel, req.productCode)
    if rule_set_id is None:
        raise _validation_error("No applicable fare rule set found")

    causation_id = _command_id()
    try:
        with service.transaction():
            quote = service.compute_fare_quote(
                quote_id=prefixed_uuid7("fq"),
                input_hash=_contract_input_hash(list(req.segmentRefs), req.channel, list(req.travelerRefs)),
                traveler_refs=req.travelerRefs,
                channel=req.channel,
                rule_set_id=rule_set_id,
                requested_currency="CNY",
                segment_refs=req.segmentRefs,
                seat_class=req.seatClass,
                distance_km=req.distanceKm,
                departure_time=_effective_datetime(req.departureTime) if req.departureTime is not None else None,
            )
            envelope = _event_envelope(request, "FareQuoteComputed", causation_id, _fare_quote_event_payload(quote))
            service.append_outbox((envelope,))
            if getattr(request.app.state, "publish_events_synchronously", True):
                try:
                    request.app.state.domain_event_service._publisher.publish(envelope)
                except PublishFailed as exc:
                    raise ApiError("UNAVAILABLE", "Event bus publish failed", 503) from exc
    except OptimisticConcurrencyError as exc:
        raise _conflict_error(exc) from exc
    except (PricingError, RuleSetNotFoundError) as exc:
        raise _domain_error(exc) from exc

    resp = _fare_quote_to_response(quote)
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
) -> dict[str, Any]:
    service: FarePricingService = request.app.state.fare_pricing_service
    purpose = AssessmentPurpose.REFUND if req.purpose == "REFUND" else AssessmentPurpose.CHANGE
    original_quote_id = service.find_fare_quote_id_for_segments(req.segmentRefs)
    if original_quote_id is None:
        raise ApiError("PRECONDITION_FAILED", "Original fare quote not found for requested segments", 412)
    original_quote = service.get_fare_quote(original_quote_id)
    rule_set_id = service.find_published_rule_set_id(original_quote.channel, original_quote.product_code)
    if rule_set_id is None:
        raise _validation_error("No applicable fare rule set found")

    causation_id = _command_id()
    try:
        with service.transaction():
            aq = service.compute_adjustment_quote(
                assessment_id=prefixed_uuid7("fa"),
                adjustment_quote_id=prefixed_uuid7("aq"),
                purpose=purpose,
                original_quote_id=original_quote_id,
                rule_set_id=rule_set_id,
                target_quote_id=original_quote_id if purpose == AssessmentPurpose.CHANGE else None,
            )
            envelope = _event_envelope(request, "AdjustmentQuoteComputed", causation_id, _adjustment_quote_event_payload(aq, req))
            service.append_outbox((envelope,))
            if getattr(request.app.state, "publish_events_synchronously", True):
                try:
                    request.app.state.domain_event_service._publisher.publish(envelope)
                except PublishFailed as exc:
                    raise ApiError("UNAVAILABLE", "Event bus publish failed", 503) from exc
    except OptimisticConcurrencyError as exc:
        raise _conflict_error(exc) from exc
    except (PricingError, QuoteNotFoundError, RuleSetNotFoundError) as exc:
        raise _domain_error(exc) from exc

    resp = _adjustment_quote_to_response(aq)
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


def _fare_rule_set_published_payload(rule_set: FareRuleSet) -> dict[str, Any]:
    return {
        "ruleSetId": rule_set.rule_set_id,
        "supplierId": rule_set.supplier_id,
        "contractId": rule_set.contract_id,
        "productCode": rule_set.product_code,
        "mode": rule_set.mode,
        "channel": rule_set.channel,
        "version": rule_set.version,
        "status": rule_set.status.value.upper(),
        "effectiveWindow": {
            "startsAt": _timestamp_str(rule_set.effective_window.starts_at),
            "endsAt": _timestamp_str(rule_set.effective_window.ends_at),
        },
        "publishedAt": _timestamp_str(rule_set.published_at or datetime.now(UTC)),
        "rules": [_rule_to_schema(rule) for rule in rule_set.rules],
    }


def _fare_rule_set_superseded_payload(old_rule_set: FareRuleSet, new_rule_set: FareRuleSet) -> dict[str, Any]:
    return {
        "ruleSetId": old_rule_set.rule_set_id,
        "supersededByRuleSetId": new_rule_set.rule_set_id,
        "supplierId": old_rule_set.supplier_id,
        "contractId": old_rule_set.contract_id,
        "productCode": old_rule_set.product_code,
        "mode": old_rule_set.mode,
        "channel": old_rule_set.channel,
        "version": old_rule_set.version,
        "status": old_rule_set.status.value.upper(),
        "supersededAt": _timestamp_str(new_rule_set.published_at or datetime.now(UTC)),
    }


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
