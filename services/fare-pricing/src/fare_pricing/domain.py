from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
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
class FareRule:
    rule_id: str
    kind: RuleKind
    amount: Money
    explanation: PriceExplanation
    refundable: bool = True

    def __post_init__(self) -> None:
        if not self.rule_id.strip():
            raise PricingError("fare rule id is required")
        if self.amount.amount < Decimal("0.00"):
            raise PricingError("fare rule amount cannot be negative")


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
    total: Money = field(init=False)

    def __post_init__(self) -> None:
        currency = self.base_fare.currency
        total = self.base_fare
        for component in self.taxes + self.fees + self.discounts:
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
        if total.amount < Decimal("0.00"):
            raise PricingError("fare breakdown total cannot be negative")
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
        if self.status is RuleSetStatus.PUBLISHED and self.published_at is None:
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
            normalized_currency,
            QuoteStatus.FAILED,
            quoted_at,
            quote_until,
            failed_reason=failure,
        )

    base_rule = next(rule for rule in rule_set.rules if rule.kind is RuleKind.BASE_FARE)
    taxes = tuple(_component(rule) for rule in rule_set.rules if rule.kind is RuleKind.TAX)
    fees = tuple(_component(rule) for rule in rule_set.rules if rule.kind is RuleKind.FEE)
    discounts = tuple(_component(rule) for rule in rule_set.rules if rule.kind is RuleKind.DISCOUNT)
    breakdown = FareBreakdown(base_rule.amount, taxes, fees, discounts)
    snapshot = RuleSnapshot.from_rule_set(rule_set, quoted_at)
    return FareQuote(
        quote_id,
        input_hash,
        tuple(traveler_refs),
        channel,
        normalized_currency,
        QuoteStatus.QUOTED,
        quoted_at,
        quote_until,
        snapshot,
        breakdown,
        tuple(rule.explanation for rule in rule_set.rules),
    )


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
