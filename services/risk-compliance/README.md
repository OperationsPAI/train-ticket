# risk-compliance

Domain: Risk & Compliance

Language: python

Phase: phase-1-support

Status: skeleton

## Owns

- RiskAssessment
- Challenge
- BlockDecision
- EvidenceSummary

## DDD Sources

- `docs/02-domains/risk-compliance.md`

## Language Rationale

Python supports rule experimentation, scoring, and evidence summarization without coupling source domains.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
