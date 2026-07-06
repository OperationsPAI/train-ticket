from __future__ import annotations

import unittest
from dataclasses import FrozenInstanceError
from datetime import UTC, datetime, timedelta
from decimal import Decimal

from fare_pricing import (
    AssessmentPurpose,
    FareQuote,
    FareRule,
    FareRuleSet,
    Money,
    PriceExplanation,
    PricingError,
    QuoteStatus,
    RuleKind,
    RuleSetStatus,
    RuleSnapshot,
    ValidityWindow,
    assess_change,
    assess_refund,
    calculate_fare_quote,
)


NOW = datetime(2026, 7, 3, 12, 0, tzinfo=UTC)


def rule(rule_id: str, kind: RuleKind, amount: str, *, refundable: bool = True) -> FareRule:
    return FareRule(
        rule_id=rule_id,
        kind=kind,
        amount=Money(amount, "USD"),
        explanation=PriceExplanation(f"fare.{rule_id}", {"rule": rule_id}),
        refundable=refundable,
    )


def draft_rule_set(*rules: FareRule) -> FareRuleSet:
    return FareRuleSet(
        rule_set_id="ruleset-main",
        supplier_id="supplier-a",
        product_code="rail-flex",
        mode="rail",
        channel="web",
        version="2026.07.03",
        effective_window=ValidityWindow(NOW - timedelta(days=1), NOW + timedelta(days=30)),
        rules=rules,
    )


def published_rule_set(*extra_rules: FareRule) -> FareRuleSet:
    return draft_rule_set(
        rule("base", RuleKind.BASE_FARE, "100.00"),
        rule("tax", RuleKind.TAX, "7.50"),
        rule("platform", RuleKind.FEE, "5.00", refundable=False),
        rule("member", RuleKind.DISCOUNT, "12.50"),
        *extra_rules,
    ).publish(NOW)


def quoted(rule_set: FareRuleSet | None = None, quote_id: str = "quote-1"):
    return calculate_fare_quote(
        quote_id=quote_id,
        input_hash="hash-123",
        traveler_refs=("traveler-1",),
        channel="web",
        rule_set=rule_set or published_rule_set(),
        requested_currency="USD",
        quoted_at=NOW,
        ttl=timedelta(minutes=15),
    )


class FarePricingDomainTest(unittest.TestCase):
    def test_rule_set_validation_requires_one_base_fare_and_one_currency(self) -> None:
        with self.assertRaisesRegex(PricingError, "exactly one base fare"):
            draft_rule_set(rule("tax-only", RuleKind.TAX, "1.00")).validate_for_publication()

        with self.assertRaisesRegex(PricingError, "cannot mix currencies"):
            draft_rule_set(
                rule("base", RuleKind.BASE_FARE, "10.00"),
                FareRule("eur-tax", RuleKind.TAX, Money("1.00", "EUR"), PriceExplanation("tax.eur")),
            ).validate_for_publication()

        published = draft_rule_set(rule("base", RuleKind.BASE_FARE, "10.00")).publish(NOW)
        self.assertEqual(published.status, RuleSetStatus.PUBLISHED)
        with self.assertRaisesRegex(PricingError, "immutable"):
            published.add_rule(rule("late-fee", RuleKind.FEE, "1.00"))

    def test_quote_calculation_enforces_total_and_currency_consistency(self) -> None:
        quote = quoted()

        self.assertEqual(quote.status, QuoteStatus.QUOTED)
        self.assertIsNotNone(quote.breakdown)
        assert quote.breakdown is not None
        self.assertEqual(quote.breakdown.total, Money("100.00", "USD"))
        self.assertEqual(quote.currency, "USD")
        self.assertLess(quote.valid_from, quote.valid_until)

        failed = calculate_fare_quote(
            quote_id="quote-currency-fail",
            input_hash="hash-456",
            traveler_refs=("traveler-1",),
            channel="web",
            rule_set=published_rule_set(),
            requested_currency="EUR",
            quoted_at=NOW,
            ttl=timedelta(minutes=15),
        )
        self.assertEqual(failed.status, QuoteStatus.FAILED)
        self.assertEqual(failed.failed_reason, "requested currency does not match fare rule set currency")

    def test_rule_snapshot_and_quoted_values_are_immutable(self) -> None:
        quote = quoted()
        assert quote.rule_snapshot is not None
        original_digest = quote.rule_snapshot.digest
        original_rule_ids = quote.rule_snapshot.rule_ids

        changed_rule_set = published_rule_set(rule("refund", RuleKind.REFUND_FEE, "20.00"))
        changed_snapshot = RuleSnapshot.from_rule_set(changed_rule_set, NOW)

        self.assertEqual(quote.rule_snapshot.digest, original_digest)
        self.assertEqual(quote.rule_snapshot.rule_ids, original_rule_ids)
        self.assertNotEqual(changed_snapshot.digest, original_digest)
        with self.assertRaises(FrozenInstanceError):
            quote.breakdown = None  # type: ignore[misc]
        with self.assertRaises(TypeError):
            quote.rule_snapshot.rule_ids[0] = "mutated"  # type: ignore[index]

    def test_quote_expiry_is_explicit_and_blocks_early_expire_transition(self) -> None:
        quote = quoted()

        self.assertFalse(quote.is_expired(NOW + timedelta(minutes=14, seconds=59)))
        self.assertTrue(quote.is_expired(NOW + timedelta(minutes=15)))
        with self.assertRaisesRegex(PricingError, "before valid_until"):
            quote.expire(NOW + timedelta(minutes=1))

        expired = quote.expire(NOW + timedelta(minutes=15))
        self.assertEqual(expired.status, QuoteStatus.EXPIRED)
        self.assertEqual(expired.breakdown, quote.breakdown)

    def test_failed_quote_requires_explicit_reason(self) -> None:
        with self.assertRaisesRegex(PricingError, "explicit reason"):
            FareQuote(
                quote_id="failed-without-reason",
                input_hash="hash",
                traveler_refs=("traveler-1",),
                channel="web",
                product_code="rail-flex",
                currency="USD",
                status=QuoteStatus.FAILED,
                valid_from=NOW,
                valid_until=NOW + timedelta(minutes=1),
            )

    def test_basic_refund_fee_assessment(self) -> None:
        original = quoted()
        adjustment = assess_refund(
            assessment_id="assessment-refund",
            adjustment_quote_id="adjustment-refund",
            original_quote=original,
            rule_set=published_rule_set(rule("refund", RuleKind.REFUND_FEE, "20.00")),
            assessed_at=NOW + timedelta(minutes=1),
            ttl=timedelta(minutes=10),
        )

        self.assertEqual(adjustment.purpose, AssessmentPurpose.REFUND)
        self.assertEqual(adjustment.status, QuoteStatus.QUOTED)
        self.assertEqual(adjustment.fee_assessment.fee, Money("20.00", "USD"))
        self.assertEqual(adjustment.refundable_amount, Money("75.00", "USD"))
        self.assertEqual(adjustment.amount_due, Money("0.00", "USD"))
        self.assertTrue(adjustment.fee_assessment.succeeded)

        failed = assess_refund(
            assessment_id="assessment-refund-failed",
            adjustment_quote_id="adjustment-refund-failed",
            original_quote=original,
            rule_set=published_rule_set(),
            assessed_at=NOW,
            ttl=timedelta(minutes=10),
        )
        self.assertEqual(failed.status, QuoteStatus.FAILED)
        self.assertEqual(failed.failed_reason, "no refund fee rule applies")

    def test_basic_change_fee_assessment_and_fare_difference(self) -> None:
        original = quoted(quote_id="original")
        target = quoted(
            draft_rule_set(
                rule("base", RuleKind.BASE_FARE, "130.00"),
                rule("tax", RuleKind.TAX, "7.50"),
                rule("platform", RuleKind.FEE, "5.00", refundable=False),
                rule("member", RuleKind.DISCOUNT, "12.50"),
            ).publish(NOW),
            quote_id="target",
        )

        adjustment = assess_change(
            assessment_id="assessment-change",
            adjustment_quote_id="adjustment-change",
            original_quote=original,
            target_quote=target,
            rule_set=published_rule_set(rule("change", RuleKind.CHANGE_FEE, "15.00")),
            assessed_at=NOW + timedelta(minutes=1),
            ttl=timedelta(minutes=10),
        )

        self.assertEqual(adjustment.purpose, AssessmentPurpose.CHANGE)
        self.assertEqual(adjustment.status, QuoteStatus.QUOTED)
        self.assertEqual(adjustment.fare_difference, Money("30.00", "USD"))
        self.assertEqual(adjustment.amount_due, Money("45.00", "USD"))
        self.assertEqual(adjustment.refundable_amount, Money("0.00", "USD"))
        self.assertEqual(adjustment.target_quote_id, "target")


if __name__ == "__main__":
    unittest.main()
