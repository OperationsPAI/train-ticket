# fare-pricing

Domain: Fare & Pricing

Language: python

Phase: phase-1-core

Status: skeleton

## Owns

- FareRuleSet
- FareQuote
- RefundFee
- ChangeFee
- RuleSnapshot

## DDD Sources

- `docs/02-domains/fare-pricing.md`

## Language Rationale

Python keeps fare rules, explanation tooling, and later experimentation lightweight.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
