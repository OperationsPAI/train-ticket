from __future__ import annotations

from contextlib import nullcontext
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta, timezone
from typing import Any

from fare_pricing.domain import (
    AdjustmentQuote,
    AssessmentPurpose,
    FareQuote,
    CapacitySnapshot,
    EligibilityCertificateSummary,
    FareRuleSet,
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
    capacity_snapshots: dict[tuple[str, str], CapacitySnapshot] = field(default_factory=dict)
    eligibility_certificates: dict[str, EligibilityCertificateSummary] = field(default_factory=dict)
    processed_events: set[str] = field(default_factory=set)

    def transaction(self) -> Any:
        return nullcontext()

    def all_rule_sets(self) -> tuple[FareRuleSet, ...]:
        return tuple(self.fare_rule_sets.values())

    def published_rule_sets(self, channel: str, product_code: str) -> tuple[FareRuleSet, ...]:
        return tuple(
            rule_set
            for rule_set in self.fare_rule_sets.values()
            if rule_set.status == RuleSetStatus.PUBLISHED and rule_set.channel == channel and rule_set.product_code == product_code
        )

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

    def save_rule_set(self, rule_set: FareRuleSet) -> None:
        self.fare_rule_sets[rule_set.rule_set_id] = rule_set

    def append_outbox(self, envelopes: tuple[Any, ...]) -> None:
        return None

    def get_adjustment_quote(self, adjustment_quote_id: str) -> AdjustmentQuote:
        if adjustment_quote_id not in self.adjustment_quotes:
            raise AdjustmentQuoteNotFoundError(f"Adjustment quote not found: {adjustment_quote_id}")
        return self.adjustment_quotes[adjustment_quote_id]

    def upsert_capacity_snapshot(self, snapshot: CapacitySnapshot) -> None:
        key = (snapshot.segment_ref, snapshot.departure_date.isoformat())
        current = self.capacity_snapshots.get(key)
        if current is None or snapshot.snapshot_version >= current.snapshot_version:
            self.capacity_snapshots[key] = snapshot

    def capacity_snapshots_for(self, segment_refs: list[str], departure_date: str | None) -> tuple[CapacitySnapshot, ...]:
        if departure_date is None:
            return ()
        return tuple(
            snapshot
            for segment_ref in segment_refs
            if (snapshot := self.capacity_snapshots.get((segment_ref, departure_date))) is not None
        )

    def try_mark_processed(self, event_id: str, stream: str | None = None) -> bool:
        if event_id in self.processed_events:
            return False
        self.processed_events.add(event_id)
        return True

    def upsert_eligibility_certificate(self, certificate: EligibilityCertificateSummary) -> None:
        current = self.eligibility_certificates.get(certificate.eligibility_certificate_id)
        if current is None or certificate.aggregate_version >= current.aggregate_version:
            self.eligibility_certificates[certificate.eligibility_certificate_id] = certificate

    def update_eligibility_usage_counts(self, certificate_id: str, reserved: int, confirmed: int, aggregate_version: int) -> None:
        current = self.eligibility_certificates.get(certificate_id)
        if current is not None:
            self.eligibility_certificates[certificate_id] = current.with_usage_counts(reserved, confirmed, aggregate_version)

    def active_eligibility_certificates_for(
        self, traveler_id: str, eligibility_type: str, journey_date: str, product_code: str
    ) -> tuple[EligibilityCertificateSummary, ...]:
        target_date = date.fromisoformat(journey_date)
        return tuple(
            certificate
            for certificate in self.eligibility_certificates.values()
            if certificate.is_active_for(traveler_id, eligibility_type, target_date, product_code)
        )


class EligibilityCertificatePort:
    def has_active_certificate(self, traveler_id: str, eligibility_type: str, journey_date: str, product_code: str) -> bool:
        return False


class FarePricingService:
    """Application service that orchestrates fare pricing operations."""

    def __init__(self, store: Any, eligibility: EligibilityCertificatePort | None = None) -> None:
        self._store = store
        self._eligibility = eligibility or EligibilityCertificatePort()

    def transaction(self) -> Any:
        transaction = getattr(self._store, "transaction", None)
        return transaction() if callable(transaction) else nullcontext()

    def append_outbox(self, envelopes: tuple[Any, ...]) -> None:
        append = getattr(self._store, "append_outbox", None)
        if callable(append):
            append(envelopes)

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
        seat_class: str = "SECOND_CLASS",
        distance_km: float | str | None = None,
        departure_time: datetime | None = None,
    ) -> FareQuote:
        now = quoted_at or datetime.now(timezone.utc)
        ttl = ttl or timedelta(minutes=15)
        rule_set = self._store.get_rule_set(rule_set_id)

        departure_date = departure_time.astimezone(UTC).date().isoformat() if departure_time is not None else None
        journey_date = departure_date or now.date().isoformat()
        active_discount_types = self._active_discount_types(rule_set, traveler_refs, journey_date)
        capacity_snapshots = self.capacity_snapshots_for(segment_refs or [], departure_date)
        quote = calculate_fare_quote(
            quote_id=quote_id,
            input_hash=input_hash,
            traveler_refs=traveler_refs,
            channel=channel,
            rule_set=rule_set,
            requested_currency=requested_currency,
            quoted_at=now,
            ttl=ttl,
            active_discount_types=active_discount_types,
            seat_class=seat_class,
            distance_km=distance_km,
            departure_time=departure_time,
            capacity_snapshots=capacity_snapshots,
        )
        self._store.save_quote(quote)
        self.link_fare_quote_to_segments(quote.quote_id, segment_refs or [])
        return quote

    def _active_discount_types(self, rule_set: FareRuleSet, traveler_refs: list[str], journey_date: str) -> set[str]:
        requested = {rule.explanation.as_mapping().get("eligibilityType", "").strip().upper() for rule in rule_set.rules if rule.kind.value == "discount"}
        requested.discard("")
        if not requested:
            return set()
        active: set[str] = set()
        for eligibility_type in requested:
            if any(self._has_active_certificate(traveler, eligibility_type, journey_date, rule_set.product_code) for traveler in traveler_refs):
                active.add(eligibility_type)
        return active

    def _has_active_certificate(self, traveler_id: str, eligibility_type: str, journey_date: str, product_code: str) -> bool:
        lookup = getattr(self._store, "active_eligibility_certificates_for", None)
        if callable(lookup):
            try:
                if lookup(traveler_id, eligibility_type, journey_date, product_code):
                    return True
            except ValueError:
                return False
        return self._eligibility.has_active_certificate(traveler_id, eligibility_type, journey_date, product_code)

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

    def create_rule_set(self, rule_set: FareRuleSet) -> FareRuleSet:
        try:
            existing = self._store.get_rule_set(rule_set.rule_set_id)
        except RuleSetNotFoundError:
            existing = None
        if existing is not None:
            if existing != rule_set:
                raise PricingError(f"fare rule set already exists with different content: {rule_set.rule_set_id}")
            return existing
        self._store.save_rule_set(rule_set)
        return rule_set

    def publish_rule_set(
        self, rule_set_id: str, published_at: datetime | None = None
    ) -> tuple[FareRuleSet, tuple[FareRuleSet, ...], bool, tuple[FareRuleSet, ...]]:
        now = published_at or datetime.now(UTC)
        rule_set = self._store.get_rule_set(rule_set_id)
        if rule_set.status == RuleSetStatus.PUBLISHED:
            return rule_set, (), False, ()
        if rule_set.status not in {RuleSetStatus.DRAFT, RuleSetStatus.VALIDATED}:
            raise PricingError(f"fare rule set cannot be published from status {rule_set.status.value}")
        published = rule_set.publish(now)
        originals: list[FareRuleSet] = [rule_set]
        superseded: list[FareRuleSet] = []
        for existing in list(self._store.all_rule_sets() if hasattr(self._store, "all_rule_sets") else self._store.fare_rule_sets.values()):
            if (
                existing.rule_set_id != published.rule_set_id
                and existing.status == RuleSetStatus.PUBLISHED
                and existing.channel == published.channel
                and existing.product_code == published.product_code
            ):
                originals.append(existing)
                old = existing.supersede()
                self._store.save_rule_set(old)
                superseded.append(old)
        self._store.save_rule_set(published)
        return published, tuple(superseded), True, tuple(originals)

    def restore_rule_sets(self, rule_sets: tuple[FareRuleSet, ...]) -> None:
        for rule_set in rule_sets:
            self._store.save_rule_set(rule_set)

    def find_published_rule_set_id(self, channel: str, product_code: str, at: datetime | None = None) -> str | None:
        when = at or datetime.now(UTC)
        if hasattr(self._store, "published_rule_sets"):
            source = self._store.published_rule_sets(channel, product_code)
        else:
            source = self._store.fare_rule_sets.values()
        candidates = [
            rule_set
            for rule_set in source
            if rule_set.status == RuleSetStatus.PUBLISHED
            and rule_set.channel == channel
            and rule_set.is_effective(when)
            and rule_set.product_code == product_code
        ]
        if not candidates:
            return None
        candidates.sort(key=lambda rs: (rs.published_at or datetime.min.replace(tzinfo=UTC), rs.version, rs.rule_set_id), reverse=True)
        return candidates[0].rule_set_id

    def link_fare_quote_to_segments(self, quote_id: str, segment_refs: list[str]) -> None:
        self._store.get_quote(quote_id)
        normalized_refs = tuple(sorted(ref for ref in segment_refs if ref.strip()))
        if normalized_refs:
            if hasattr(self._store, "link_fare_quote_to_segments") and not isinstance(self._store, InMemoryStore):
                self._store.link_fare_quote_to_segments(quote_id, normalized_refs)
            else:
                self._store.fare_quote_segment_links[quote_id] = normalized_refs

    def find_fare_quote_id_for_segments(self, segment_refs: list[str]) -> str | None:
        if hasattr(self._store, "find_fare_quote_id_for_segments") and not isinstance(self._store, InMemoryStore):
            return self._store.find_fare_quote_id_for_segments(segment_refs)
        requested_segments = {ref.strip() for ref in segment_refs if ref.strip()}
        if not requested_segments:
            return None
        # Several quotes can exist for the same segments (repeat purchases,
        # repriced rule sets); the most recent one is the adjustment's
        # original. fq-<uuid7> ids are time-ordered, so max() is newest.
        matches = [
            quote_id
            for quote_id, linked_segments in self._store.fare_quote_segment_links.items()
            if requested_segments.issubset(set(linked_segments))
        ]
        return max(matches) if matches else None

    def get_fare_quote(self, quote_id: str) -> FareQuote:
        return self._store.get_quote(quote_id)

    def get_adjustment_quote(self, adjustment_quote_id: str) -> AdjustmentQuote:
        return self._store.get_adjustment_quote(adjustment_quote_id)

    def upsert_capacity_snapshot(self, snapshot: CapacitySnapshot) -> None:
        upsert = getattr(self._store, "upsert_capacity_snapshot", None)
        if callable(upsert):
            upsert(snapshot)

    def capacity_snapshots_for(self, segment_refs: list[str], departure_date: str | None) -> tuple[CapacitySnapshot, ...]:
        if departure_date is None:
            return ()
        lookup = getattr(self._store, "capacity_snapshots_for", None)
        if callable(lookup):
            return lookup(segment_refs, departure_date)
        return ()

    def mark_event_processed(self, event_id: str, stream: str) -> bool:
        mark = getattr(self._store, "try_mark_processed", None)
        if callable(mark):
            try:
                return bool(mark(event_id, stream))
            except TypeError:
                return bool(mark(event_id))
        processed = getattr(self._store, "processed_events", None)
        if processed is None:
            return True
        if event_id in processed:
            return False
        processed.add(event_id)
        return True

    def upsert_eligibility_certificate(self, certificate: EligibilityCertificateSummary) -> None:
        upsert = getattr(self._store, "upsert_eligibility_certificate", None)
        if callable(upsert):
            upsert(certificate)

    def update_eligibility_usage_counts(self, certificate_id: str, reserved: int, confirmed: int, aggregate_version: int) -> None:
        update = getattr(self._store, "update_eligibility_usage_counts", None)
        if callable(update):
            update(certificate_id, reserved, confirmed, aggregate_version)
