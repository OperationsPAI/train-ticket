from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any

from fare_pricing.domain import (
    AdjustmentQuote,
    AssessmentPurpose,
    FareQuote,
    FareRuleSet,
    QuoteStatus,
    RuleSetStatus,
    PricingError,
    calculate_fare_quote,
    assess_refund,
    assess_change,
)


class QuoteNotFoundError(Exception):
    """Raised when a quote is not found."""


class AdjustmentQuoteNotFoundError(Exception):
    """Raised when an adjustment quote is not found."""


class RuleSetNotFoundError(Exception):
    """Raised when a fare rule set is not found."""


@dataclass
class InMemoryStore:
    """Simple in-memory store for quotes and rule sets."""

    fare_rule_sets: dict[str, FareRuleSet] = field(default_factory=dict)
    fare_quotes: dict[str, FareQuote] = field(default_factory=dict)
    adjustment_quotes: dict[str, AdjustmentQuote] = field(default_factory=dict)
    fare_quote_segment_links: dict[str, tuple[str, ...]] = field(default_factory=dict)

    def get_rule_set(self, rule_set_id: str) -> FareRuleSet:
        if rule_set_id not in self.fare_rule_sets:
            raise RuleSetNotFoundError(f"Fare rule set not found: {rule_set_id}")
        return self.fare_rule_sets[rule_set_id]

    def save_quote(self, quote: FareQuote) -> None:
        self.fare_quotes[quote.quote_id] = quote

    def get_quote(self, quote_id: str) -> FareQuote:
        if quote_id not in self.fare_quotes:
            raise QuoteNotFoundError(f"Fare quote not found: {quote_id}")
        return self.fare_quotes[quote_id]

    def save_adjustment_quote(self, aq: AdjustmentQuote) -> None:
        self.adjustment_quotes[aq.adjustment_quote_id] = aq

    def get_adjustment_quote(self, adjustment_quote_id: str) -> AdjustmentQuote:
        if adjustment_quote_id not in self.adjustment_quotes:
            raise AdjustmentQuoteNotFoundError(f"Adjustment quote not found: {adjustment_quote_id}")
        return self.adjustment_quotes[adjustment_quote_id]


class FarePricingService:
    """Application service that orchestrates fare pricing operations."""

    def __init__(self, store: InMemoryStore) -> None:
        self._store = store

    def compute_fare_quote(
        self,
        quote_id: str,
        input_hash: str,
        traveler_refs: list[str],
        channel: str,
        rule_set_id: str,
        requested_currency: str,
        segment_refs: list[str] | None = None,
        quoted_at: datetime | None = None,
        ttl: timedelta | None = None,
    ) -> FareQuote:
        now = quoted_at or datetime.now(timezone.utc)
        ttl = ttl or timedelta(minutes=15)
        rule_set = self._store.get_rule_set(rule_set_id)

        quote = calculate_fare_quote(
            quote_id=quote_id,
            input_hash=input_hash,
            traveler_refs=traveler_refs,
            channel=channel,
            rule_set=rule_set,
            requested_currency=requested_currency,
            quoted_at=now,
            ttl=ttl,
        )
        self._store.save_quote(quote)
        self.link_fare_quote_to_segments(quote.quote_id, segment_refs or [])
        return quote

    def compute_adjustment_quote(
        self,
        assessment_id: str,
        adjustment_quote_id: str,
        purpose: AssessmentPurpose,
        original_quote_id: str,
        rule_set_id: str,
        target_quote_id: str | None = None,
        assessed_at: datetime | None = None,
        ttl: timedelta | None = None,
    ) -> AdjustmentQuote:
        now = assessed_at or datetime.now(timezone.utc)
        ttl = ttl or timedelta(minutes=10)
        original_quote = self._store.get_quote(original_quote_id)
        rule_set = self._store.get_rule_set(rule_set_id)

        if purpose == AssessmentPurpose.REFUND:
            aq = assess_refund(
                assessment_id=assessment_id,
                adjustment_quote_id=adjustment_quote_id,
                original_quote=original_quote,
                rule_set=rule_set,
                assessed_at=now,
                ttl=ttl,
            )
        elif purpose == AssessmentPurpose.CHANGE:
            if target_quote_id is None:
                raise PricingError("target_quote_id is required for CHANGE assessments")
            target_quote = self._store.get_quote(target_quote_id)
            aq = assess_change(
                assessment_id=assessment_id,
                adjustment_quote_id=adjustment_quote_id,
                original_quote=original_quote,
                target_quote=target_quote,
                rule_set=rule_set,
                assessed_at=now,
                ttl=ttl,
            )
        else:
            raise PricingError(f"Unknown assessment purpose: {purpose}")

        self._store.save_adjustment_quote(aq)
        return aq

    def find_published_rule_set_id(self, channel: str) -> str | None:
        for rule_set_id, rule_set in self._store.fare_rule_sets.items():
            if rule_set.status == RuleSetStatus.PUBLISHED and rule_set.channel == channel:
                return rule_set_id
        return None

    def link_fare_quote_to_segments(self, quote_id: str, segment_refs: list[str]) -> None:
        if quote_id not in self._store.fare_quotes:
            raise QuoteNotFoundError(f"Fare quote not found: {quote_id}")
        normalized_refs = tuple(sorted(ref for ref in segment_refs if ref.strip()))
        if normalized_refs:
            self._store.fare_quote_segment_links[quote_id] = normalized_refs

    def find_fare_quote_id_for_entitlements(self, entitlement_ids: list[str]) -> str | None:
        requested_segments = self._segment_refs_from_entitlements(entitlement_ids)
        if not requested_segments:
            return None
        requested_set = set(requested_segments)
        for quote_id, segment_refs in self._store.fare_quote_segment_links.items():
            if requested_set.issubset(set(segment_refs)):
                return quote_id
        return None

    @staticmethod
    def _segment_refs_from_entitlements(entitlement_ids: list[str]) -> tuple[str, ...]:
        segment_refs: list[str] = []
        for entitlement_id in entitlement_ids:
            if not entitlement_id.strip():
                continue
            if entitlement_id.startswith("seg-"):
                segment_refs.append(entitlement_id)
                continue
            if entitlement_id.startswith("ent-"):
                segment_refs.append("seg-" + entitlement_id.removeprefix("ent-"))
                continue
            segment_refs.append(entitlement_id)
        return tuple(sorted(segment_refs))

    def get_fare_quote(self, quote_id: str) -> FareQuote:
        return self._store.get_quote(quote_id)

    def get_adjustment_quote(self, adjustment_quote_id: str) -> AdjustmentQuote:
        return self._store.get_adjustment_quote(adjustment_quote_id)
