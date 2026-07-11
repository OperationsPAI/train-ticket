from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
import json
from typing import Any

from train_ticket_platform.storage import OptimisticConcurrencyError, OutboxAppender, SnapshotRepository

from fare_pricing.domain import (
    AdjustmentQuote,
    AssessmentPurpose,
    AdvancePurchaseTier,
    CapacitySnapshot,
    FareBreakdown,
    FareQuote,
    FareRule,
    FareRuleSet,
    FeeAssessment,
    Money,
    PriceComponent,
    PriceExplanation,
    QuoteStatus,
    DEFAULT_ADVANCE_PURCHASE_TIERS,
    DEFAULT_SEAT_CLASS_MULTIPLIERS,
    RuleKind,
    RuleSetStatus,
    RuleSnapshot,
    ValidityWindow,
)
from fare_pricing.application.service import AdjustmentQuoteNotFoundError, QuoteNotFoundError, RuleSetNotFoundError
from fare_pricing.ports import EventEnvelope


def _dt(value: datetime) -> str:
    aware = value if value.tzinfo is not None else value.replace(tzinfo=UTC)
    return aware.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def _parse_dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def _money_to_json(money: Money) -> dict[str, Any]:
    return {"currency": money.currency, "minorUnits": money.amount_minor}


def _money_from_json(data: Mapping[str, Any]) -> Money:
    return Money.from_minor(int(data["minorUnits"]), str(data["currency"]))


def _explanation_to_json(explanation: PriceExplanation) -> dict[str, Any]:
    return {"code": explanation.code, "parameters": dict(explanation.as_mapping())}


def _explanation_from_json(data: Mapping[str, Any]) -> PriceExplanation:
    return PriceExplanation(str(data["code"]), data.get("parameters") or {})


def _component_to_json(component: PriceComponent) -> dict[str, Any]:
    return {
        "ruleId": component.rule_id,
        "amount": _money_to_json(component.amount),
        "explanation": _explanation_to_json(component.explanation),
        "refundable": component.refundable,
    }


def _component_from_json(data: Mapping[str, Any]) -> PriceComponent:
    return PriceComponent(
        str(data["ruleId"]),
        _money_from_json(data["amount"]),
        _explanation_from_json(data["explanation"]),
        bool(data["refundable"]),
    )


def _breakdown_to_json(breakdown: FareBreakdown | None) -> dict[str, Any] | None:
    if breakdown is None:
        return None
    return {
        "baseFare": _money_to_json(breakdown.base_fare),
        "taxes": [_component_to_json(item) for item in breakdown.taxes],
        "fees": [_component_to_json(item) for item in breakdown.fees],
        "discounts": [_component_to_json(item) for item in breakdown.discounts],
        "dynamicAdjustments": [_component_to_json(item) for item in breakdown.dynamic_adjustments],
        "pricingComponents": dict(breakdown.pricing_components),
    }


def _breakdown_from_json(data: Mapping[str, Any] | None) -> FareBreakdown | None:
    if data is None:
        return None
    return FareBreakdown(
        _money_from_json(data["baseFare"]),
        tuple(_component_from_json(item) for item in data.get("taxes", ())),
        tuple(_component_from_json(item) for item in data.get("fees", ())),
        tuple(_component_from_json(item) for item in data.get("discounts", ())),
        tuple(_component_from_json(item) for item in data.get("dynamicAdjustments", ())),
        data.get("pricingComponents") or {},
    )


def _snapshot_to_json(snapshot: RuleSnapshot | None) -> dict[str, Any] | None:
    if snapshot is None:
        return None
    return {
        "ruleSetId": snapshot.rule_set_id,
        "ruleSetVersion": snapshot.rule_set_version,
        "capturedAt": _dt(snapshot.captured_at),
        "ruleIds": list(snapshot.rule_ids),
        "explanationCodes": list(snapshot.explanation_codes),
        "digest": snapshot.digest,
    }


def _snapshot_from_json(data: Mapping[str, Any] | None) -> RuleSnapshot | None:
    if data is None:
        return None
    return RuleSnapshot(
        str(data["ruleSetId"]),
        str(data["ruleSetVersion"]),
        _parse_dt(str(data["capturedAt"])),
        tuple(str(item) for item in data.get("ruleIds", ())),
        tuple(str(item) for item in data.get("explanationCodes", ())),
        str(data["digest"]),
    )


def _rule_to_json(rule: FareRule) -> dict[str, Any]:
    return {
        "ruleId": rule.rule_id,
        "kind": rule.kind.value,
        "amount": _money_to_json(rule.amount),
        "explanation": _explanation_to_json(rule.explanation),
        "refundable": rule.refundable,
        "seatClassMultipliers": {key: str(value) for key, value in rule.seat_class_multipliers.items()},
        "perKmRate": str(rule.per_km_rate) if rule.per_km_rate is not None else None,
        "minimumFare": _money_to_json(rule.minimum_fare) if rule.minimum_fare is not None else None,
        "distanceDiscountThresholdKm": str(rule.distance_discount_threshold_km) if rule.distance_discount_threshold_km is not None else None,
        "distanceDiscountPct": rule.distance_discount_pct,
    }


def _rule_from_json(data: Mapping[str, Any]) -> FareRule:
    return FareRule(
        rule_id=str(data["ruleId"]),
        kind=RuleKind(str(data["kind"])),
        amount=_money_from_json(data["amount"]),
        explanation=_explanation_from_json(data["explanation"]),
        refundable=bool(data["refundable"]),
        seat_class_multipliers={str(key): value for key, value in (data.get("seatClassMultipliers") or DEFAULT_SEAT_CLASS_MULTIPLIERS).items()},
        per_km_rate=data.get("perKmRate"),
        minimum_fare=_money_from_json(data["minimumFare"]) if data.get("minimumFare") else None,
        distance_discount_threshold_km=data.get("distanceDiscountThresholdKm"),
        distance_discount_pct=int(data.get("distanceDiscountPct") or 0),
    )


def _rule_set_to_json(rule_set: FareRuleSet) -> dict[str, Any]:
    return {
        "ruleSetId": rule_set.rule_set_id,
        "supplierId": rule_set.supplier_id,
        "contractId": rule_set.contract_id,
        "productCode": rule_set.product_code,
        "mode": rule_set.mode,
        "channel": rule_set.channel,
        "version": rule_set.version,
        "effectiveWindow": {"startsAt": _dt(rule_set.effective_window.starts_at), "endsAt": _dt(rule_set.effective_window.ends_at)},
        "rules": [_rule_to_json(rule) for rule in rule_set.rules],
        "status": rule_set.status.value,
        "publishedAt": _dt(rule_set.published_at) if rule_set.published_at else None,
        "advancePurchaseTiers": [
            {
                "minDaysBefore": tier.min_days_before,
                "maxDaysBefore": tier.max_days_before,
                "multiplier": str(tier.multiplier),
                "explanationCode": tier.explanation_code,
            }
            for tier in rule_set.advance_purchase_tiers
        ],
    }


def _json_obj(data: Mapping[str, Any] | str) -> Mapping[str, Any]:
    return json.loads(data) if isinstance(data, str) else data


def _rule_set_from_json(data: Mapping[str, Any] | str) -> FareRuleSet:
    data = _json_obj(data)
    window = data["effectiveWindow"]
    return FareRuleSet(
        rule_set_id=str(data["ruleSetId"]),
        supplier_id=str(data["supplierId"]),
        product_code=str(data["productCode"]),
        mode=str(data["mode"]),
        channel=str(data["channel"]),
        version=str(data["version"]),
        effective_window=ValidityWindow(_parse_dt(str(window["startsAt"])), _parse_dt(str(window["endsAt"]))),
        rules=tuple(_rule_from_json(item) for item in data.get("rules", ())),
        status=RuleSetStatus(str(data["status"])),
        published_at=_parse_dt(str(data["publishedAt"])) if data.get("publishedAt") else None,
        contract_id=str(data.get("contractId") or ""),
        advance_purchase_tiers=tuple(
            AdvancePurchaseTier(
                int(item["minDaysBefore"]),
                int(item["maxDaysBefore"]) if item.get("maxDaysBefore") is not None else None,
                item["multiplier"],
                str(item["explanationCode"]),
            )
            for item in data.get("advancePurchaseTiers", ())
        ) or DEFAULT_ADVANCE_PURCHASE_TIERS,
    )


def _quote_to_json(quote: FareQuote) -> dict[str, Any]:
    return {
        "quoteId": quote.quote_id,
        "inputHash": quote.input_hash,
        "travelerRefs": list(quote.traveler_refs),
        "channel": quote.channel,
        "productCode": quote.product_code,
        "currency": quote.currency,
        "status": quote.status.value,
        "validFrom": _dt(quote.valid_from),
        "validUntil": _dt(quote.valid_until),
        "ruleSnapshot": _snapshot_to_json(quote.rule_snapshot),
        "breakdown": _breakdown_to_json(quote.breakdown),
        "explanations": [_explanation_to_json(item) for item in quote.explanations],
        "failedReason": quote.failed_reason,
    }


def _quote_from_json(data: Mapping[str, Any] | str) -> FareQuote:
    data = _json_obj(data)
    return FareQuote(
        quote_id=str(data["quoteId"]),
        input_hash=str(data["inputHash"]),
        traveler_refs=tuple(str(item) for item in data.get("travelerRefs", ())),
        channel=str(data["channel"]),
        product_code=str(data["productCode"]),
        currency=str(data["currency"]),
        status=QuoteStatus(str(data["status"])),
        valid_from=_parse_dt(str(data["validFrom"])),
        valid_until=_parse_dt(str(data["validUntil"])),
        rule_snapshot=_snapshot_from_json(data.get("ruleSnapshot")),
        breakdown=_breakdown_from_json(data.get("breakdown")),
        explanations=tuple(_explanation_from_json(item) for item in data.get("explanations", ())),
        failed_reason=data.get("failedReason"),
    )


def _fee_assessment_to_json(assessment: FeeAssessment) -> dict[str, Any]:
    return {
        "assessmentId": assessment.assessment_id,
        "purpose": assessment.purpose.value,
        "assessedAt": _dt(assessment.assessed_at),
        "ruleSnapshot": _snapshot_to_json(assessment.rule_snapshot),
        "originalQuoteId": assessment.original_quote_id,
        "fee": _money_to_json(assessment.fee) if assessment.fee else None,
        "currency": assessment.currency,
        "failedReason": assessment.failed_reason,
    }


def _fee_assessment_from_json(data: Mapping[str, Any]) -> FeeAssessment:
    fee_data = data.get("fee")
    return FeeAssessment(
        assessment_id=str(data["assessmentId"]),
        purpose=AssessmentPurpose(str(data["purpose"])),
        assessed_at=_parse_dt(str(data["assessedAt"])),
        rule_snapshot=_snapshot_from_json(data.get("ruleSnapshot")),
        original_quote_id=str(data["originalQuoteId"]),
        fee=_money_from_json(fee_data) if fee_data else None,
        currency=str(data["currency"]),
        failed_reason=data.get("failedReason"),
    )


def _adjustment_quote_to_json(quote: AdjustmentQuote) -> dict[str, Any]:
    return {
        "adjustmentQuoteId": quote.adjustment_quote_id,
        "purpose": quote.purpose.value,
        "status": quote.status.value,
        "originalQuoteId": quote.original_quote_id,
        "validFrom": _dt(quote.valid_from),
        "validUntil": _dt(quote.valid_until),
        "feeAssessment": _fee_assessment_to_json(quote.fee_assessment),
        "refundableAmount": _money_to_json(quote.refundable_amount),
        "amountDue": _money_to_json(quote.amount_due),
        "fareDifference": _money_to_json(quote.fare_difference) if quote.fare_difference else None,
        "targetQuoteId": quote.target_quote_id,
        "failedReason": quote.failed_reason,
    }


def _adjustment_quote_from_json(data: Mapping[str, Any] | str) -> AdjustmentQuote:
    data = _json_obj(data)
    fare_difference = data.get("fareDifference")
    return AdjustmentQuote(
        adjustment_quote_id=str(data["adjustmentQuoteId"]),
        purpose=AssessmentPurpose(str(data["purpose"])),
        status=QuoteStatus(str(data["status"])),
        original_quote_id=str(data["originalQuoteId"]),
        valid_from=_parse_dt(str(data["validFrom"])),
        valid_until=_parse_dt(str(data["validUntil"])),
        fee_assessment=_fee_assessment_from_json(data["feeAssessment"]),
        refundable_amount=_money_from_json(data["refundableAmount"]),
        amount_due=_money_from_json(data["amountDue"]),
        fare_difference=_money_from_json(fare_difference) if fare_difference else None,
        target_quote_id=data.get("targetQuoteId"),
        failed_reason=data.get("failedReason"),
    )


def domain_to_json(entity: FareRuleSet | FareQuote | AdjustmentQuote) -> dict[str, Any]:
    if isinstance(entity, FareRuleSet):
        return _rule_set_to_json(entity)
    if isinstance(entity, FareQuote):
        return _quote_to_json(entity)
    return _adjustment_quote_to_json(entity)


@dataclass(slots=True)
class _UnitOfWorkState:
    connection: Any | None = None
    loaded_versions: dict[tuple[str, str], int] = field(default_factory=dict)


_UNIT_OF_WORK: ContextVar[_UnitOfWorkState | None] = ContextVar("fare_pricing_postgres_uow", default=None)


class PostgresFarePricingStore:
    """Postgres adapter preserving the in-memory store interface for fare-pricing."""

    def __init__(self, pool: Any, outbox: OutboxAppender | None = None) -> None:
        self._pool = pool
        self._outbox = outbox or OutboxAppender()
        self._rule_sets = SnapshotRepository("fare_rule_set_snapshots")
        self._quotes = SnapshotRepository("fare_quote_snapshots")
        self._adjustment_quotes = SnapshotRepository("adjustment_quote_snapshots")

    @contextmanager
    def transaction(self):
        state = _UNIT_OF_WORK.get()
        if state is not None and state.connection is not None:
            yield state.connection
            return
        with self._pool.connection() as conn:
            with conn.transaction():
                token = _UNIT_OF_WORK.set(_UnitOfWorkState(connection=conn))
                try:
                    yield conn
                finally:
                    _UNIT_OF_WORK.reset(token)

    @contextmanager
    def unit_of_work(self):
        """Create an isolated identity map for a request that manages its own connection lifecycle."""
        if _UNIT_OF_WORK.get() is not None:
            yield
            return
        token = _UNIT_OF_WORK.set(_UnitOfWorkState())
        try:
            yield
        finally:
            _UNIT_OF_WORK.reset(token)

    def _with_conn(self, func: Callable[[Any], Any]) -> Any:
        state = _UNIT_OF_WORK.get()
        if state is not None and state.connection is not None:
            return func(state.connection)
        with self._pool.connection() as conn:
            return func(conn)

    @staticmethod
    def _version_map() -> dict[tuple[str, str], int] | None:
        state = _UNIT_OF_WORK.get()
        return state.loaded_versions if state is not None else None

    def _remember_version(self, aggregate: str, aggregate_id: str, version: int) -> None:
        versions = self._version_map()
        if versions is not None:
            versions[(aggregate, aggregate_id)] = version

    def _take_loaded_version(self, aggregate: str, aggregate_id: str) -> int | None:
        versions = self._version_map()
        if versions is None:
            return None
        return versions.pop((aggregate, aggregate_id), None)

    @staticmethod
    def _raise_conflict(exc: OptimisticConcurrencyError) -> None:
        raise exc

    def get_rule_set(self, rule_set_id: str) -> FareRuleSet:
        def read(conn: Any) -> FareRuleSet:
            snapshot = self._rule_sets.get(conn, rule_set_id)
            if snapshot is None:
                raise RuleSetNotFoundError(f"Fare rule set not found: {rule_set_id}")
            version, data = snapshot
            self._remember_version("rule_set", rule_set_id, version)
            return _rule_set_from_json(data)

        return self._with_conn(read)

    def save_rule_set(self, rule_set: FareRuleSet) -> None:
        def write(conn: Any) -> None:
            expected_version = self._take_loaded_version("rule_set", rule_set.rule_set_id)
            try:
                self._rule_sets.save(conn, rule_set.rule_set_id, _rule_set_to_json(rule_set), expected_version)
            except OptimisticConcurrencyError as exc:
                self._raise_conflict(exc)

        self._with_conn(write)

    def save_quote(self, quote: FareQuote) -> None:
        def write(conn: Any) -> None:
            expected_version = self._take_loaded_version("quote", quote.quote_id)
            try:
                self._quotes.save(conn, quote.quote_id, _quote_to_json(quote), expected_version)
            except OptimisticConcurrencyError as exc:
                self._raise_conflict(exc)

        self._with_conn(write)

    def get_quote(self, quote_id: str) -> FareQuote:
        def read(conn: Any) -> FareQuote:
            snapshot = self._quotes.get(conn, quote_id)
            if snapshot is None:
                raise QuoteNotFoundError(f"Fare quote not found: {quote_id}")
            version, data = snapshot
            self._remember_version("quote", quote_id, version)
            return _quote_from_json(data)

        return self._with_conn(read)

    def save_adjustment_quote(self, aq: AdjustmentQuote) -> None:
        def write(conn: Any) -> None:
            expected_version = self._take_loaded_version("adjustment_quote", aq.adjustment_quote_id)
            try:
                self._adjustment_quotes.save(
                    conn, aq.adjustment_quote_id, _adjustment_quote_to_json(aq), expected_version
                )
            except OptimisticConcurrencyError as exc:
                self._raise_conflict(exc)

        self._with_conn(write)

    def get_adjustment_quote(self, adjustment_quote_id: str) -> AdjustmentQuote:
        def read(conn: Any) -> AdjustmentQuote:
            snapshot = self._adjustment_quotes.get(conn, adjustment_quote_id)
            if snapshot is None:
                raise AdjustmentQuoteNotFoundError(f"Adjustment quote not found: {adjustment_quote_id}")
            version, data = snapshot
            self._remember_version("adjustment_quote", adjustment_quote_id, version)
            return _adjustment_quote_from_json(data)

        return self._with_conn(read)

    def all_rule_sets(self) -> tuple[FareRuleSet, ...]:
        def read(conn: Any) -> tuple[FareRuleSet, ...]:
            rows = conn.execute("SELECT id, version, data FROM fare_rule_set_snapshots").fetchall()
            rule_sets = []
            for aggregate_id, version, data in rows:
                self._remember_version("rule_set", str(aggregate_id), int(version))
                rule_sets.append(_rule_set_from_json(data))
            return tuple(rule_sets)

        return self._with_conn(read)

    def published_rule_sets(self, channel: str, product_code: str) -> tuple[FareRuleSet, ...]:
        def read(conn: Any) -> tuple[FareRuleSet, ...]:
            rows = conn.execute(
                "SELECT id, version, data FROM fare_rule_set_snapshots "
                "WHERE data->>'status' = 'published' AND data->>'channel' = %s AND data->>'productCode' = %s",
                (channel, product_code),
            ).fetchall()
            rule_sets = []
            for aggregate_id, version, data in rows:
                self._remember_version("rule_set", str(aggregate_id), int(version))
                rule_sets.append(_rule_set_from_json(data))
            return tuple(rule_sets)

        return self._with_conn(read)

    def link_fare_quote_to_segments(self, quote_id: str, segment_refs: Iterable[str]) -> None:
        refs = tuple(sorted(ref.strip() for ref in segment_refs if ref.strip()))
        if not refs:
            return

        def write(conn: Any) -> None:
            conn.execute("DELETE FROM fare_quote_segment_links WHERE quote_id = %s", (quote_id,))
            for ref in refs:
                conn.execute("INSERT INTO fare_quote_segment_links(quote_id, segment_ref) VALUES (%s, %s)", (quote_id, ref))

        self._with_conn(write)

    def find_fare_quote_id_for_segments(self, segment_refs: Iterable[str]) -> str | None:
        refs = tuple(sorted({ref.strip() for ref in segment_refs if ref.strip()}))
        if not refs:
            return None

        def read(conn: Any) -> str | None:
            row = conn.execute(
                "SELECT quote_id FROM fare_quote_segment_links WHERE segment_ref = ANY(%s) "
                "GROUP BY quote_id HAVING COUNT(DISTINCT segment_ref) = %s ORDER BY quote_id DESC LIMIT 1",
                (list(refs), len(refs)),
            ).fetchone()
            return str(row[0]) if row else None

        return self._with_conn(read)

    def upsert_capacity_snapshot(self, snapshot: CapacitySnapshot) -> None:
        def write(conn: Any) -> None:
            conn.execute(
                "INSERT INTO capacity_snapshot_cache(segment_ref, departure_date, total_capacity, remaining_capacity, snapshot_version) "
                "VALUES (%s, %s, %s, %s, %s) "
                "ON CONFLICT (segment_ref, departure_date) DO UPDATE SET "
                "total_capacity = EXCLUDED.total_capacity, remaining_capacity = EXCLUDED.remaining_capacity, "
                "snapshot_version = EXCLUDED.snapshot_version, updated_at = now() "
                "WHERE capacity_snapshot_cache.snapshot_version <= EXCLUDED.snapshot_version",
                (
                    snapshot.segment_ref,
                    snapshot.departure_date,
                    snapshot.total_capacity,
                    snapshot.remaining_capacity,
                    snapshot.snapshot_version,
                ),
            )

        self._with_conn(write)

    def capacity_snapshots_for(self, segment_refs: Iterable[str], departure_date: str | None) -> tuple[CapacitySnapshot, ...]:
        refs = tuple(sorted({ref.strip() for ref in segment_refs if ref.strip()}))
        if not refs or departure_date is None:
            return ()
        target_date = date.fromisoformat(departure_date)

        def read(conn: Any) -> tuple[CapacitySnapshot, ...]:
            rows = conn.execute(
                "SELECT segment_ref, departure_date, total_capacity, remaining_capacity, snapshot_version "
                "FROM capacity_snapshot_cache WHERE segment_ref = ANY(%s) AND departure_date = %s",
                (list(refs), target_date),
            ).fetchall()
            return tuple(
                CapacitySnapshot(str(row[0]), row[1], int(row[2]), int(row[3]), int(row[4]))
                for row in rows
            )

        return self._with_conn(read)

    def append_outbox(self, envelopes: Iterable[EventEnvelope]) -> None:
        def write(conn: Any) -> None:
            for envelope in envelopes:
                self._outbox.append(conn, envelope)

        self._with_conn(write)
