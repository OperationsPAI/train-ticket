from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum
from hashlib import sha256
from types import MappingProxyType
from typing import Iterable, Mapping, Self


class PricingError(ValueError):
    """Raised when fare-pricing invariants are violated."""


class RuleKind(str, Enum):
    BASE_FARE = "base_fare"
    TAX = "tax"
    FEE = "fee"
    DISCOUNT = "discount"
    REFUND_FEE = "refund_fee"
    CHANGE_FEE = "change_fee"


class RuleSetStatus(str, Enum):
    DRAFT = "draft"
    VALIDATED = "validated"
    PUBLISHED = "published"
    SUSPENDED = "suspended"
    SUPERSEDED = "superseded"
    EXPIRED = "expired"


class QuoteStatus(str, Enum):
    DRAFT = "draft"
    QUOTED = "quoted"
    ACCEPTED = "accepted"
    EXPIRED = "expired"
    SUPERSEDED = "superseded"
    FAILED = "failed"


class AssessmentPurpose(str, Enum):
    REFUND = "refund"
    CHANGE = "change"


DEFAULT_SEAT_CLASS_MULTIPLIERS: Mapping[str, Decimal] = MappingProxyType(
    {
        "SECOND_CLASS": Decimal("1.0"),
        "FIRST_CLASS": Decimal("1.6"),
        "BUSINESS_CLASS": Decimal("2.8"),
        "STANDING": Decimal("0.7"),
        "SLEEPER_HARD": Decimal("1.8"),
        "SLEEPER_SOFT": Decimal("2.5"),
    }
)


class SeatClassMultiplier(str, Enum):
    SECOND_CLASS = "SECOND_CLASS"
    FIRST_CLASS = "FIRST_CLASS"
    BUSINESS_CLASS = "BUSINESS_CLASS"
    STANDING = "STANDING"
    SLEEPER_HARD = "SLEEPER_HARD"
    SLEEPER_SOFT = "SLEEPER_SOFT"

    @property
    def multiplier(self) -> Decimal:
        return DEFAULT_SEAT_CLASS_MULTIPLIERS[self.value]


@dataclass(frozen=True, slots=True)
class Money:
    amount: Decimal
    currency: str

    def __init__(self, amount: Decimal | str | int, currency: str) -> None:
        normalized_currency = currency.strip().upper()
        if len(normalized_currency) != 3 or not normalized_currency.isalpha():
            raise PricingError("currency must be a three-letter ISO code")
        quantized = Decimal(str(amount)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
        object.__setattr__(self, "amount", quantized)
        object.__setattr__(self, "currency", normalized_currency)

    @property
    def amount_minor(self) -> int:
        """Cross-context canonical form: integer minor units (cents/fen)."""
        return int(self.amount * Decimal("100"))

    def require_same_currency(self, other: Self) -> None:
        if self.currency != other.currency:
            raise PricingError(f"currency mismatch: {self.currency} != {other.currency}")

    def __add__(self, other: Self) -> Self:
        self.require_same_currency(other)
        return Money(self.amount + other.amount, self.currency)

    def __sub__(self, other: Self) -> Self:
        self.require_same_currency(other)
        return Money(self.amount - other.amount, self.currency)

    def max_zero(self) -> Self:
        return Money(max(self.amount, Decimal("0.00")), self.currency)

    @classmethod
    def zero(cls, currency: str) -> Self:
        return cls(Decimal("0.00"), currency)

    @classmethod
    def from_minor(cls, amount_minor: int, currency: str) -> Self:
        """Construct Money from canonical minor units."""
        return cls(Decimal(amount_minor) / Decimal("100"), currency)


@dataclass(frozen=True, slots=True)
class ValidityWindow:
    starts_at: datetime
    ends_at: datetime

    def __post_init__(self) -> None:
        if self.ends_at <= self.starts_at:
            raise PricingError("validity window must end after it starts")

    def contains(self, when: datetime) -> bool:
        return self.starts_at <= when < self.ends_at


@dataclass(frozen=True, slots=True)
class PriceExplanation:
    code: str
    parameters: tuple[tuple[str, str], ...] = field(default_factory=tuple)

    def __init__(self, code: str, parameters: Mapping[str, object] | None = None) -> None:
        normalized_code = code.strip()
        if not normalized_code:
            raise PricingError("price explanation code is required")
        items = tuple(sorted((str(key), str(value)) for key, value in (parameters or {}).items()))
        object.__setattr__(self, "code", normalized_code)
        object.__setattr__(self, "parameters", items)

    def as_mapping(self) -> Mapping[str, str]:
        return MappingProxyType(dict(self.parameters))


@dataclass(frozen=True, slots=True)
class AdvancePurchaseTier:
    min_days_before: int
    max_days_before: int | None
    multiplier: Decimal
    explanation_code: str

    def __post_init__(self) -> None:
        if self.min_days_before < 0:
            raise PricingError("advance purchase tier minimum days cannot be negative")
        if self.max_days_before is not None and self.max_days_before < self.min_days_before:
            raise PricingError("advance purchase tier maximum days must be >= minimum days")
        object.__setattr__(self, "multiplier", Decimal(str(self.multiplier)))
        if self.multiplier < Decimal("0.00"):
            raise PricingError("advance purchase multiplier cannot be negative")
        if not self.explanation_code.strip():
            raise PricingError("advance purchase explanation code is required")

    def matches(self, days_before_departure: int) -> bool:
        return days_before_departure >= self.min_days_before and (
            self.max_days_before is None or days_before_departure <= self.max_days_before
        )


@dataclass(frozen=True, slots=True)
class PeakPricingRule:
    date_ranges: tuple[tuple[date, date], ...] = field(default_factory=tuple)
    hour_ranges: tuple[tuple[int, int], ...] = ((7, 9), (17, 19))
    date_surcharge_pct: int = 30
    hour_surcharge_pct: int = 15
    offpeak_discount_pct: int = 10

    def __post_init__(self) -> None:
        for start, end in self.date_ranges:
            if end < start:
                raise PricingError("peak date range must end on or after its start")
        for start_hour, end_hour in self.hour_ranges:
            if not 0 <= start_hour <= 23 or not 0 <= end_hour <= 24 or end_hour <= start_hour:
                raise PricingError("peak hour range must be within 00-24 and end after start")
        for pct in (self.date_surcharge_pct, self.hour_surcharge_pct, self.offpeak_discount_pct):
            if pct < 0:
                raise PricingError("peak pricing percentages cannot be negative")

    def is_peak_date(self, departure_date: date) -> bool:
        return departure_date.weekday() >= 5 or any(start <= departure_date <= end for start, end in self.date_ranges)

    def is_peak_hour(self, departure_time: time) -> bool:
        return any(start <= departure_time.hour < end for start, end in self.hour_ranges)


@dataclass(frozen=True, slots=True)
class DynamicPricingBand:
    min_remaining_pct: Decimal
    max_remaining_pct: Decimal
    adjustment_pct: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "min_remaining_pct", Decimal(str(self.min_remaining_pct)))
        object.__setattr__(self, "max_remaining_pct", Decimal(str(self.max_remaining_pct)))
        if self.min_remaining_pct < Decimal("0") or self.max_remaining_pct > Decimal("100"):
            raise PricingError("dynamic pricing bands must stay within 0-100 percent")
        if self.max_remaining_pct < self.min_remaining_pct:
            raise PricingError("dynamic pricing band max must be >= min")
        if self.adjustment_pct < 0:
            raise PricingError("dynamic pricing adjustment cannot be negative")

    def matches(self, remaining_pct: Decimal) -> bool:
        return self.min_remaining_pct <= remaining_pct < self.max_remaining_pct


@dataclass(frozen=True, slots=True)
class CapacitySnapshot:
    segment_ref: str
    departure_date: date
    total_capacity: int
    remaining_capacity: int
    snapshot_version: int

    def __post_init__(self) -> None:
        if not self.segment_ref.strip():
            raise PricingError("capacity snapshot segment ref is required")
        if self.total_capacity <= 0:
            raise PricingError("capacity snapshot total capacity must be positive")
        if self.remaining_capacity < 0 or self.remaining_capacity > self.total_capacity:
            raise PricingError("capacity snapshot remaining capacity must be within total capacity")
        if self.snapshot_version < 0:
            raise PricingError("capacity snapshot version cannot be negative")

    @property
    def remaining_pct(self) -> Decimal:
        return (Decimal(self.remaining_capacity) * Decimal("100") / Decimal(self.total_capacity)).quantize(Decimal("0.01"))


ELIGIBILITY_TYPES = frozenset({"STUDENT", "CHILD", "MILITARY_DISABLED"})
CERTIFICATE_STATUSES = frozenset({"DRAFT", "ACTIVE", "REJECTED", "EXPIRED", "REVOKED"})


@dataclass(frozen=True, slots=True)
class EligibilityCertificateSummary:
    eligibility_certificate_id: str
    traveler_id: str
    eligibility_type: str
    certificate_status: str
    valid_from: datetime
    valid_until: datetime
    policy_year: str
    policy_version: str
    annual_usage_limit: int
    annual_usage_reserved: int
    annual_usage_confirmed: int
    applicable_product_codes: tuple[str, ...]
    aggregate_version: int
    credential_record_id: str | None = None
    identity_cluster_id: str | None = None

    def __post_init__(self) -> None:
        if not self.eligibility_certificate_id.strip():
            raise PricingError("eligibility certificate id is required")
        if not self.traveler_id.strip():
            raise PricingError("eligibility certificate traveler id is required")
        normalized_type = self.eligibility_type.strip().upper()
        if normalized_type not in ELIGIBILITY_TYPES:
            raise PricingError(f"unsupported eligibility type: {self.eligibility_type}")
        normalized_status = self.certificate_status.strip().upper()
        if normalized_status not in CERTIFICATE_STATUSES:
            raise PricingError(f"unsupported certificate status: {self.certificate_status}")
        if self.valid_until <= self.valid_from:
            raise PricingError("eligibility certificate validity must end after it starts")
        if self.annual_usage_limit < 0:
            raise PricingError("annual usage limit cannot be negative")
        if self.annual_usage_reserved < 0 or self.annual_usage_confirmed < 0:
            raise PricingError("annual usage counters cannot be negative")
        if self.aggregate_version < 0:
            raise PricingError("eligibility certificate aggregate version cannot be negative")
        product_codes = tuple(sorted({code.strip() for code in self.applicable_product_codes if code.strip()}))
        object.__setattr__(self, "eligibility_type", normalized_type)
        object.__setattr__(self, "certificate_status", normalized_status)
        object.__setattr__(self, "applicable_product_codes", product_codes)

    def with_usage_counts(self, reserved: int, confirmed: int, aggregate_version: int) -> Self:
        if aggregate_version < self.aggregate_version:
            return self
        return EligibilityCertificateSummary(
            self.eligibility_certificate_id,
            self.traveler_id,
            self.eligibility_type,
            self.certificate_status,
            self.valid_from,
            self.valid_until,
            self.policy_year,
            self.policy_version,
            self.annual_usage_limit,
            reserved,
            confirmed,
            self.applicable_product_codes,
            aggregate_version,
            self.credential_record_id,
            self.identity_cluster_id,
        )

    def is_active_for(self, traveler_id: str, eligibility_type: str, journey_date: date, product_code: str) -> bool:
        if self.traveler_id != traveler_id or self.eligibility_type != eligibility_type.strip().upper():
            return False
        if self.certificate_status != "ACTIVE":
            return False
        if product_code not in self.applicable_product_codes:
            return False
        valid_start = self.valid_from.astimezone(UTC).date()
        valid_end = self.valid_until.astimezone(UTC).date()
        if not valid_start <= journey_date <= valid_end:
            return False
        if self.annual_usage_limit == 0:
            return False
        return (self.annual_usage_reserved + self.annual_usage_confirmed) < self.annual_usage_limit


DEFAULT_ADVANCE_PURCHASE_TIERS: tuple[AdvancePurchaseTier, ...] = (
    AdvancePurchaseTier(21, None, Decimal("0.70"), "ADVANCE_PURCHASE_TIER_1"),
    AdvancePurchaseTier(14, 20, Decimal("0.80"), "ADVANCE_PURCHASE_TIER_2"),
    AdvancePurchaseTier(7, 13, Decimal("0.90"), "ADVANCE_PURCHASE_TIER_3"),
    AdvancePurchaseTier(3, 6, Decimal("1.00"), "ADVANCE_PURCHASE_TIER_4"),
    AdvancePurchaseTier(0, 2, Decimal("1.20"), "ADVANCE_PURCHASE_TIER_5"),
)


DEFAULT_PEAK_PRICING = PeakPricingRule(
    date_ranges=(
        (date(2026, 2, 15), date(2026, 2, 23)),
        (date(2026, 5, 1), date(2026, 5, 5)),
        (date(2026, 10, 1), date(2026, 10, 7)),
    )
)


DEFAULT_DYNAMIC_PRICING_BANDS: tuple[DynamicPricingBand, ...] = (
    DynamicPricingBand(Decimal("0"), Decimal("10"), 50),
    DynamicPricingBand(Decimal("10"), Decimal("30"), 30),
    DynamicPricingBand(Decimal("30"), Decimal("50"), 15),
    DynamicPricingBand(Decimal("50"), Decimal("70.01"), 5),
)


@dataclass(frozen=True, slots=True)
class FareRule:
    rule_id: str
    kind: RuleKind
    amount: Money
    explanation: PriceExplanation
    refundable: bool = True
    seat_class_multipliers: Mapping[str, Decimal] = field(default_factory=lambda: dict(DEFAULT_SEAT_CLASS_MULTIPLIERS))
    per_km_rate: Decimal | None = None
    minimum_fare: Money | None = None
    distance_discount_threshold_km: Decimal | None = None
    distance_discount_pct: int = 0

    def __post_init__(self) -> None:
        if not self.rule_id.strip():
            raise PricingError("fare rule id is required")
        if self.amount.amount < Decimal("0.00"):
            raise PricingError("fare rule amount cannot be negative")
        if self.minimum_fare is not None and self.minimum_fare.currency != self.amount.currency:
            raise PricingError("minimum fare currency must match rule amount currency")
        if self.per_km_rate is not None and self.per_km_rate < Decimal("0.00"):
            raise PricingError("per-km rate cannot be negative")
        if self.distance_discount_threshold_km is not None and self.distance_discount_threshold_km < Decimal("0.00"):
            raise PricingError("distance discount threshold cannot be negative")
        if self.distance_discount_pct < 0 or self.distance_discount_pct > 100:
            raise PricingError("distance discount percentage must be between 0 and 100")
        normalized = {str(key).strip().upper(): Decimal(str(value)) for key, value in self.seat_class_multipliers.items()}
        if any(value < Decimal("0.00") for value in normalized.values()):
            raise PricingError("seat class multipliers cannot be negative")
        object.__setattr__(self, "seat_class_multipliers", MappingProxyType(normalized))


@dataclass(frozen=True, slots=True)
class PriceComponent:
    rule_id: str
    amount: Money
    explanation: PriceExplanation
    refundable: bool = True


@dataclass(frozen=True, slots=True)
class FareBreakdown:
    base_fare: Money
    taxes: tuple[PriceComponent, ...] = field(default_factory=tuple)
    fees: tuple[PriceComponent, ...] = field(default_factory=tuple)
    discounts: tuple[PriceComponent, ...] = field(default_factory=tuple)
    dynamic_adjustments: tuple[PriceComponent, ...] = field(default_factory=tuple)
    pricing_components: Mapping[str, object] = field(default_factory=dict)
    total: Money = field(init=False)

    def __post_init__(self) -> None:
        currency = self.base_fare.currency
        if isinstance(self.dynamic_adjustments, Mapping):
            object.__setattr__(self, "pricing_components", self.dynamic_adjustments)
            object.__setattr__(self, "dynamic_adjustments", ())
        total = self.base_fare
        for component in self.taxes + self.fees + self.discounts + self.dynamic_adjustments:
            if component.amount.currency != currency:
                raise PricingError("all fare breakdown components must share one currency")
            if component.amount.amount < Decimal("0.00"):
                raise PricingError("fare breakdown component amounts cannot be negative")
        for tax in self.taxes:
            total = total + tax.amount
        for fee in self.fees:
            total = total + fee.amount
        for discount in self.discounts:
            total = total - discount.amount
        for adjustment in self.dynamic_adjustments:
            total = total + adjustment.amount
        if total.amount < Decimal("0.00"):
            raise PricingError("fare breakdown total cannot be negative")
        object.__setattr__(self, "pricing_components", MappingProxyType(dict(self.pricing_components)))
        object.__setattr__(self, "total", total)


@dataclass(frozen=True, slots=True)
class FareRuleSet:
    rule_set_id: str
    supplier_id: str
    product_code: str
    mode: str
    channel: str
    version: str
    effective_window: ValidityWindow
    rules: tuple[FareRule, ...] = field(default_factory=tuple)
    status: RuleSetStatus = RuleSetStatus.DRAFT
    published_at: datetime | None = None
    contract_id: str = ""
    advance_purchase_tiers: tuple[AdvancePurchaseTier, ...] = DEFAULT_ADVANCE_PURCHASE_TIERS
    peak_pricing: PeakPricingRule | None = DEFAULT_PEAK_PRICING
    dynamic_pricing_bands: tuple[DynamicPricingBand, ...] = DEFAULT_DYNAMIC_PRICING_BANDS

    def __post_init__(self) -> None:
        required_fields = {
            "rule_set_id": self.rule_set_id,
            "supplier_id": self.supplier_id,
            "product_code": self.product_code,
            "mode": self.mode,
            "channel": self.channel,
            "version": self.version,
        }
        for field_name, value in required_fields.items():
            if not value.strip():
                raise PricingError(f"{field_name} is required")
        if self.status in {RuleSetStatus.PUBLISHED, RuleSetStatus.SUPERSEDED} and self.published_at is None:
            raise PricingError("published rule sets must record published_at")

    def add_rule(self, rule: FareRule) -> Self:
        if self.status not in {RuleSetStatus.DRAFT, RuleSetStatus.VALIDATED}:
            raise PricingError("published fare rule sets are immutable; create a new version")
        if any(existing.rule_id == rule.rule_id for existing in self.rules):
            raise PricingError(f"duplicate fare rule id: {rule.rule_id}")
        return FareRuleSet(
            self.rule_set_id,
            self.supplier_id,
            self.product_code,
            self.mode,
            self.channel,
            self.version,
            self.effective_window,
            self.rules + (rule,),
            RuleSetStatus.DRAFT,
            None,
            self.contract_id,
            self.advance_purchase_tiers,
            self.peak_pricing,
            self.dynamic_pricing_bands,
        )

    def validate_for_publication(self) -> Self:
        self._validate_rules()
        return FareRuleSet(
            self.rule_set_id,
            self.supplier_id,
            self.product_code,
            self.mode,
            self.channel,
            self.version,
            self.effective_window,
            self.rules,
            RuleSetStatus.VALIDATED,
            None,
            self.contract_id,
            self.advance_purchase_tiers,
            self.peak_pricing,
            self.dynamic_pricing_bands,
        )

    def publish(self, approved_at: datetime | None = None) -> Self:
        self._validate_rules()
        published_at = approved_at or datetime.now(UTC)
        return FareRuleSet(
            self.rule_set_id,
            self.supplier_id,
            self.product_code,
            self.mode,
            self.channel,
            self.version,
            self.effective_window,
            self.rules,
            RuleSetStatus.PUBLISHED,
            published_at,
            self.contract_id,
            self.advance_purchase_tiers,
            self.peak_pricing,
            self.dynamic_pricing_bands,
        )

    def supersede(self) -> Self:
        if self.status is not RuleSetStatus.PUBLISHED:
            raise PricingError("only published fare rule sets can be superseded")
        return FareRuleSet(
            self.rule_set_id,
            self.supplier_id,
            self.product_code,
            self.mode,
            self.channel,
            self.version,
            self.effective_window,
            self.rules,
            RuleSetStatus.SUPERSEDED,
            self.published_at,
            self.contract_id,
            self.advance_purchase_tiers,
            self.peak_pricing,
            self.dynamic_pricing_bands,
        )

    def is_effective(self, when: datetime) -> bool:
        return self.status is RuleSetStatus.PUBLISHED and self.effective_window.contains(when)

    def _validate_rules(self) -> None:
        if not self.rules:
            raise PricingError("fare rule set must include at least one rule")
        currencies = {rule.amount.currency for rule in self.rules}
        if len(currencies) != 1:
            raise PricingError("fare rule set cannot mix currencies")
        base_rules = [rule for rule in self.rules if rule.kind is RuleKind.BASE_FARE]
        if len(base_rules) != 1:
            raise PricingError("fare rule set must include exactly one base fare rule")
        rule_ids = [rule.rule_id for rule in self.rules]
        if len(rule_ids) != len(set(rule_ids)):
            raise PricingError("fare rule ids must be unique")


@dataclass(frozen=True, slots=True)
class RuleSnapshot:
    rule_set_id: str
    rule_set_version: str
    captured_at: datetime
    rule_ids: tuple[str, ...]
    explanation_codes: tuple[str, ...]
    digest: str

    @classmethod
    def from_rule_set(cls, rule_set: FareRuleSet, captured_at: datetime) -> Self:
        rule_payload = "|".join(
            f"{rule.rule_id}:{rule.kind.value}:{rule.amount.currency}:{rule.amount.amount}:{rule.explanation.code}"
            for rule in rule_set.rules
        )
        return cls(
            rule_set.rule_set_id,
            rule_set.version,
            captured_at,
            tuple(rule.rule_id for rule in rule_set.rules),
            tuple(rule.explanation.code for rule in rule_set.rules),
            sha256(rule_payload.encode("utf-8")).hexdigest(),
        )


@dataclass(frozen=True, slots=True)
class FareQuote:
    quote_id: str
    input_hash: str
    traveler_refs: tuple[str, ...]
    channel: str
    product_code: str
    currency: str
    status: QuoteStatus
    valid_from: datetime
    valid_until: datetime
    rule_snapshot: RuleSnapshot | None = None
    breakdown: FareBreakdown | None = None
    explanations: tuple[PriceExplanation, ...] = field(default_factory=tuple)
    failed_reason: str | None = None

    def __post_init__(self) -> None:
        if not self.quote_id.strip():
            raise PricingError("quote id is required")
        if self.valid_until <= self.valid_from:
            raise PricingError("quote validity window must end after it starts")
        if self.status is QuoteStatus.QUOTED:
            if self.breakdown is None or self.rule_snapshot is None:
                raise PricingError("quoted fare quotes require a breakdown and rule snapshot")
            if self.breakdown.total.currency != self.currency:
                raise PricingError("fare quote currency must match breakdown total currency")
            if self.failed_reason is not None:
                raise PricingError("successful fare quotes cannot include a failed reason")
        if self.status is QuoteStatus.FAILED and not self.failed_reason:
            raise PricingError("failed fare quotes require an explicit reason")

    def is_expired(self, at: datetime) -> bool:
        return at >= self.valid_until

    def expire(self, at: datetime) -> Self:
        if at < self.valid_until:
            raise PricingError("cannot expire a fare quote before valid_until")
        return FareQuote(
            self.quote_id,
            self.input_hash,
            self.traveler_refs,
            self.channel,
            self.product_code,
            self.currency,
            QuoteStatus.EXPIRED,
            self.valid_from,
            self.valid_until,
            self.rule_snapshot,
            self.breakdown,
            self.explanations,
            None,
        )


@dataclass(frozen=True, slots=True)
class FeeAssessment:
    assessment_id: str
    purpose: AssessmentPurpose
    assessed_at: datetime
    rule_snapshot: RuleSnapshot | None
    original_quote_id: str
    fee: Money | None
    currency: str
    failed_reason: str | None = None

    def __post_init__(self) -> None:
        if not self.assessment_id.strip():
            raise PricingError("fee assessment id is required")
        if self.fee is not None and self.fee.currency != self.currency:
            raise PricingError("fee assessment currency must match fee currency")
        if self.fee is None and not self.failed_reason:
            raise PricingError("failed fee assessments require an explicit reason")
        if self.fee is not None and self.failed_reason:
            raise PricingError("successful fee assessments cannot include a failed reason")

    @property
    def succeeded(self) -> bool:
        return self.fee is not None


@dataclass(frozen=True, slots=True)
class AdjustmentQuote:
    adjustment_quote_id: str
    purpose: AssessmentPurpose
    status: QuoteStatus
    original_quote_id: str
    valid_from: datetime
    valid_until: datetime
    fee_assessment: FeeAssessment
    refundable_amount: Money
    amount_due: Money
    fare_difference: Money | None = None
    target_quote_id: str | None = None
    failed_reason: str | None = None

    def __post_init__(self) -> None:
        if not self.adjustment_quote_id.strip():
            raise PricingError("adjustment quote id is required")
        if self.valid_until <= self.valid_from:
            raise PricingError("adjustment quote validity window must end after it starts")
        currency = self.fee_assessment.currency
        if self.refundable_amount.currency != currency or self.amount_due.currency != currency:
            raise PricingError("adjustment quote amounts must share assessment currency")
        if self.fare_difference is not None and self.fare_difference.currency != currency:
            raise PricingError("fare difference must share assessment currency")
        if self.status is QuoteStatus.FAILED and not self.failed_reason:
            raise PricingError("failed adjustment quotes require an explicit reason")
        if self.status is QuoteStatus.QUOTED and self.failed_reason:
            raise PricingError("successful adjustment quotes cannot include a failed reason")

    def is_expired(self, at: datetime) -> bool:
        return at >= self.valid_until


def calculate_fare_quote(
    *,
    quote_id: str,
    input_hash: str,
    traveler_refs: Iterable[str],
    channel: str,
    rule_set: FareRuleSet,
    requested_currency: str,
    quoted_at: datetime,
    ttl: timedelta,
    active_discount_types: set[str] | None = None,
    seat_class: str = "SECOND_CLASS",
    distance_km: Decimal | int | str | None = None,
    departure_time: datetime | None = None,
    capacity_snapshots: Iterable[CapacitySnapshot] | None = None,
) -> FareQuote:
    normalized_currency = requested_currency.strip().upper()
    quote_until = min(quoted_at + ttl, rule_set.effective_window.ends_at)
    if quote_until <= quoted_at:
        quote_until = quoted_at + timedelta(seconds=1)
    failure = _quote_failure_reason(rule_set, normalized_currency, quoted_at, channel)
    if failure is not None:
        return FareQuote(
            quote_id,
            input_hash,
            tuple(traveler_refs),
            channel,
            rule_set.product_code,
            normalized_currency,
            QuoteStatus.FAILED,
            quoted_at,
            quote_until,
            failed_reason=failure,
        )

    base_rule = next(rule for rule in rule_set.rules if rule.kind is RuleKind.BASE_FARE)
    base_fare, pricing_components = _base_fare_for_distance(base_rule, distance_km)
    explanations: list[PriceExplanation] = [rule.explanation for rule in rule_set.rules]

    seat_class_code = (seat_class or "SECOND_CLASS").strip().upper()
    seat_multiplier = _seat_class_multiplier(base_rule, seat_class_code)
    base_fare = _multiply_money(base_fare, seat_multiplier)
    pricing_components["seatClassMultiplier"] = {"seatClass": seat_class_code, "multiplier": str(seat_multiplier)}
    explanations.append(PriceExplanation("SEAT_CLASS_MULTIPLIER", {"seatClass": seat_class_code, "multiplier": seat_multiplier}))

    advance_tier = _advance_purchase_tier(rule_set, quoted_at, departure_time)
    if advance_tier is not None:
        base_fare = _multiply_money(base_fare, advance_tier.multiplier)
        pricing_components["advancePurchaseTier"] = {
            "tierName": advance_tier.explanation_code,
            "multiplier": str(advance_tier.multiplier),
        }
        explanations.append(
            PriceExplanation(
                advance_tier.explanation_code,
                {"minDaysBefore": advance_tier.min_days_before, "multiplier": advance_tier.multiplier},
            )
        )
    else:
        pricing_components["advancePurchaseTier"] = None

    peak_adjustment_pct = _peak_adjustment_pct(rule_set.peak_pricing, departure_time)
    if peak_adjustment_pct is not None:
        base_fare = _multiply_money(base_fare, Decimal("1") + (Decimal(peak_adjustment_pct["totalAdjustmentPct"]) / Decimal("100")))
        pricing_components["peakAdjustment"] = peak_adjustment_pct
        explanations.append(PriceExplanation("PEAK_PRICING_ADJUSTMENT", peak_adjustment_pct))
    else:
        pricing_components["peakAdjustment"] = None

    taxes = tuple(_component(rule) for rule in rule_set.rules if rule.kind is RuleKind.TAX)
    fees = tuple(_component(rule) for rule in rule_set.rules if rule.kind is RuleKind.FEE)
    active_discount_types = active_discount_types or set()
    discounts = tuple(_component(rule) for rule in rule_set.rules if rule.kind is RuleKind.DISCOUNT and _discount_allowed(rule, active_discount_types))
    subtotal = FareBreakdown(base_fare, taxes, fees, discounts, pricing_components=pricing_components).total
    dynamic_adjustment, capacity_component = _dynamic_capacity_adjustment(subtotal, rule_set.dynamic_pricing_bands, capacity_snapshots)
    if dynamic_adjustment is not None:
        explanations.append(dynamic_adjustment.explanation)
        pricing_components["dynamicCapacityAdjustment"] = capacity_component
        dynamic_adjustments = (dynamic_adjustment,)
    else:
        pricing_components["dynamicCapacityAdjustment"] = capacity_component
        dynamic_adjustments = ()
    breakdown = FareBreakdown(base_fare, taxes, fees, discounts, dynamic_adjustments, pricing_components)
    snapshot = RuleSnapshot.from_rule_set(rule_set, quoted_at)
    return FareQuote(
        quote_id,
        input_hash,
        tuple(traveler_refs),
        channel,
        rule_set.product_code,
        normalized_currency,
        QuoteStatus.QUOTED,
        quoted_at,
        quote_until,
        snapshot,
        breakdown,
        tuple(explanations),
    )


def _multiply_money(money: Money, multiplier: Decimal) -> Money:
    return Money(money.amount * multiplier, money.currency)


def _base_fare_for_distance(base_rule: FareRule, distance_km: Decimal | int | str | None) -> tuple[Money, dict[str, object]]:
    if base_rule.per_km_rate is None or distance_km is None:
        return base_rule.amount, {"baseFlat": {"amount": base_rule.amount.amount_minor, "currency": base_rule.amount.currency}}
    distance = Decimal(str(distance_km))
    if distance < Decimal("0"):
        raise PricingError("distance_km cannot be negative")
    threshold = base_rule.distance_discount_threshold_km
    discount_pct = Decimal(base_rule.distance_discount_pct) / Decimal("100")
    charged_km = distance
    discounted_km = Decimal("0")
    if threshold is not None and distance > threshold and discount_pct > Decimal("0"):
        discounted_km = distance - threshold
        charged_km = threshold + (discounted_km * (Decimal("1") - discount_pct))
    distance_amount = charged_km * base_rule.per_km_rate
    minimum = base_rule.minimum_fare or Money.zero(base_rule.amount.currency)
    fare = Money(max(distance_amount, minimum.amount), base_rule.amount.currency)
    return fare, {
        "baseDistanceFare": {
            "distanceKm": str(distance),
            "perKmRate": str(base_rule.per_km_rate),
            "minimumFare": minimum.amount_minor,
            "discountThresholdKm": str(threshold) if threshold is not None else None,
            "discountPct": base_rule.distance_discount_pct,
        }
    }


def _seat_class_multiplier(base_rule: FareRule, seat_class: str) -> Decimal:
    try:
        return Decimal(str(base_rule.seat_class_multipliers[seat_class]))
    except KeyError as exc:
        raise PricingError(f"unsupported seat class: {seat_class}") from exc


def _advance_purchase_tier(rule_set: FareRuleSet, quoted_at: datetime, departure_time: datetime | None) -> AdvancePurchaseTier | None:
    if departure_time is None:
        return None
    departure = departure_time.astimezone(UTC) if departure_time.tzinfo is not None else departure_time.replace(tzinfo=UTC)
    quoted = quoted_at.astimezone(UTC) if quoted_at.tzinfo is not None else quoted_at.replace(tzinfo=UTC)
    days_before = max((departure.date() - quoted.date()).days, 0)
    return next((tier for tier in rule_set.advance_purchase_tiers if tier.matches(days_before)), None)


def _peak_adjustment_pct(peak_pricing: PeakPricingRule | None, departure_time: datetime | None) -> dict[str, int] | None:
    if peak_pricing is None or departure_time is None:
        return None
    departure = departure_time.astimezone(UTC) if departure_time.tzinfo is not None else departure_time.replace(tzinfo=UTC)
    date_adjustment = peak_pricing.date_surcharge_pct if peak_pricing.is_peak_date(departure.date()) else 0
    hour_adjustment = peak_pricing.hour_surcharge_pct if peak_pricing.is_peak_hour(departure.time()) else 0
    offpeak_discount = peak_pricing.offpeak_discount_pct if date_adjustment == 0 and hour_adjustment == 0 else 0
    total = date_adjustment + hour_adjustment - offpeak_discount
    return {
        "dateAdjustment": date_adjustment,
        "hourAdjustment": hour_adjustment,
        "offpeakDiscount": offpeak_discount,
        "totalAdjustmentPct": total,
    }


def _dynamic_capacity_adjustment(
    subtotal: Money,
    bands: tuple[DynamicPricingBand, ...],
    capacity_snapshots: Iterable[CapacitySnapshot] | None,
) -> tuple[PriceComponent | None, dict[str, object] | None]:
    snapshots = tuple(capacity_snapshots or ())
    if not snapshots:
        return None, None
    lowest = min(snapshots, key=lambda item: item.remaining_pct)
    remaining_pct = lowest.remaining_pct
    band = next((candidate for candidate in bands if candidate.matches(remaining_pct)), None)
    adjustment_pct = band.adjustment_pct if band is not None else 0
    component_data = {
        "segmentRef": lowest.segment_ref,
        "remainingPct": str(remaining_pct),
        "adjustmentPct": adjustment_pct,
    }
    if adjustment_pct == 0:
        return None, component_data
    amount = _multiply_money(subtotal, Decimal(adjustment_pct) / Decimal("100"))
    return (
        PriceComponent(
            "dynamic-capacity",
            amount,
            PriceExplanation("DYNAMIC_CAPACITY_ADJUSTMENT", component_data),
            False,
        ),
        component_data,
    )


def _discount_allowed(rule: FareRule, active_discount_types: set[str]) -> bool:
    eligibility_type = rule.explanation.as_mapping().get("eligibilityType", "").strip().upper()
    return not eligibility_type or eligibility_type in active_discount_types


def assess_refund(
    *,
    assessment_id: str,
    adjustment_quote_id: str,
    original_quote: FareQuote,
    rule_set: FareRuleSet,
    assessed_at: datetime,
    ttl: timedelta,
) -> AdjustmentQuote:
    if original_quote.breakdown is None:
        return _failed_adjustment(
            assessment_id,
            adjustment_quote_id,
            AssessmentPurpose.REFUND,
            original_quote,
            assessed_at,
            ttl,
            "original quote has no successful price breakdown",
        )
    fee_rule = _single_fee_rule(rule_set, RuleKind.REFUND_FEE)
    if fee_rule is None:
        return _failed_adjustment(
            assessment_id,
            adjustment_quote_id,
            AssessmentPurpose.REFUND,
            original_quote,
            assessed_at,
            ttl,
            "no refund fee rule applies",
        )
    snapshot = RuleSnapshot.from_rule_set(rule_set, assessed_at)
    fee = fee_rule.amount
    retained_non_refundable = sum(
        (component.amount.amount for component in original_quote.breakdown.taxes + original_quote.breakdown.fees if not component.refundable),
        Decimal("0.00"),
    )
    refund = Money(original_quote.breakdown.total.amount - retained_non_refundable - fee.amount, original_quote.currency).max_zero()
    assessment = FeeAssessment(
        assessment_id,
        AssessmentPurpose.REFUND,
        assessed_at,
        snapshot,
        original_quote.quote_id,
        fee,
        original_quote.currency,
    )
    return AdjustmentQuote(
        adjustment_quote_id,
        AssessmentPurpose.REFUND,
        QuoteStatus.QUOTED,
        original_quote.quote_id,
        assessed_at,
        assessed_at + ttl,
        assessment,
        refund,
        Money.zero(original_quote.currency),
    )


def assess_change(
    *,
    assessment_id: str,
    adjustment_quote_id: str,
    original_quote: FareQuote,
    target_quote: FareQuote,
    rule_set: FareRuleSet,
    assessed_at: datetime,
    ttl: timedelta,
) -> AdjustmentQuote:
    if original_quote.breakdown is None or target_quote.breakdown is None:
        return _failed_adjustment(
            assessment_id,
            adjustment_quote_id,
            AssessmentPurpose.CHANGE,
            original_quote,
            assessed_at,
            ttl,
            "original or target quote has no successful price breakdown",
        )
    fee_rule = _single_fee_rule(rule_set, RuleKind.CHANGE_FEE)
    if fee_rule is None:
        return _failed_adjustment(
            assessment_id,
            adjustment_quote_id,
            AssessmentPurpose.CHANGE,
            original_quote,
            assessed_at,
            ttl,
            "no change fee rule applies",
        )
    snapshot = RuleSnapshot.from_rule_set(rule_set, assessed_at)
    fee = fee_rule.amount
    fare_difference = target_quote.breakdown.total - original_quote.breakdown.total
    net_due = fare_difference + fee
    amount_due = net_due.max_zero()
    refundable = Money(-net_due.amount, net_due.currency).max_zero()
    assessment = FeeAssessment(
        assessment_id,
        AssessmentPurpose.CHANGE,
        assessed_at,
        snapshot,
        original_quote.quote_id,
        fee,
        original_quote.currency,
    )
    return AdjustmentQuote(
        adjustment_quote_id,
        AssessmentPurpose.CHANGE,
        QuoteStatus.QUOTED,
        original_quote.quote_id,
        assessed_at,
        assessed_at + ttl,
        assessment,
        refundable,
        amount_due,
        fare_difference,
        target_quote.quote_id,
    )


def _quote_failure_reason(
    rule_set: FareRuleSet, requested_currency: str, quoted_at: datetime, channel: str
) -> str | None:
    if rule_set.status is not RuleSetStatus.PUBLISHED:
        return "fare rule set is not published"
    if not rule_set.effective_window.contains(quoted_at):
        return "fare rule set is not effective at quote time"
    if rule_set.channel != channel:
        return "fare rule set channel does not match quote channel"
    try:
        rule_set._validate_rules()
    except PricingError as exc:
        return str(exc)
    if {rule.amount.currency for rule in rule_set.rules} != {requested_currency}:
        return "requested currency does not match fare rule set currency"
    return None


def _component(rule: FareRule) -> PriceComponent:
    return PriceComponent(rule.rule_id, rule.amount, rule.explanation, rule.refundable)


def _single_fee_rule(rule_set: FareRuleSet, kind: RuleKind) -> FareRule | None:
    matches = [rule for rule in rule_set.rules if rule.kind is kind]
    if len(matches) != 1:
        return None
    return matches[0]


def _failed_adjustment(
    assessment_id: str,
    adjustment_quote_id: str,
    purpose: AssessmentPurpose,
    original_quote: FareQuote,
    assessed_at: datetime,
    ttl: timedelta,
    reason: str,
) -> AdjustmentQuote:
    assessment = FeeAssessment(
        assessment_id,
        purpose,
        assessed_at,
        None,
        original_quote.quote_id,
        None,
        original_quote.currency,
        reason,
    )
    return AdjustmentQuote(
        adjustment_quote_id,
        purpose,
        QuoteStatus.FAILED,
        original_quote.quote_id,
        assessed_at,
        assessed_at + ttl,
        assessment,
        Money.zero(original_quote.currency),
        Money.zero(original_quote.currency),
        failed_reason=reason,
    )
