# fare-pricing

Domain: Fare & Pricing

Language: python

Phase: phase-1-core

Status: tested domain foundation

## Owns

- FareRuleSet
- FareRule
- FareQuote
- RuleSnapshot
- FareBreakdown
- PriceExplanation
- FeeAssessment
- AdjustmentQuote

## Delivered Foundation (REQ-007)

This service now contains the first coherent Fare & Pricing bounded-context
foundation in `src/fare_pricing/domain.py`:

- versioned `FareRuleSet` publication with draft/validated/published status and
  immutable published rule contents;
- `FareRule` support for base fare, tax, fee, discount, refund-fee, and
  change-fee rule kinds;
- `FareQuote` calculation with immutable `RuleSnapshot`, `FareBreakdown`,
  `PriceExplanation`, quote status, and validity window;
- invariant enforcement for `total = base fare + taxes + fees - discounts`, one
  currency per rule set / quote / assessment, and explicit failed quote reasons;
- post-sales `FeeAssessment` and `AdjustmentQuote` basics for refund and change
  fee assessment, including fare difference and explicit failure reasons.

Boundary reminders: this service does **not** own Offer lifecycle, Journey Order
lifecycle, Payment execution, provider raw state, inventory availability, or
PostSalesCase state. Downstream contexts freeze or execute the snapshots and
amounts produced here.

## DDD Sources

- `docs/02-domains/fare-pricing.md`

## Language Rationale

Python keeps fare rules, explanation tooling, and later experimentation lightweight.

## Validation

```bash
uv run python -m unittest discover -s tests
```
