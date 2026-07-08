# disruption-recovery

> **Status — future-scope skeleton**
>
> This service is **not deployed**, is **not part of the current 23-service set**,
> and remains **future-scope** until activated by the roadmap in
> [`docs/04-implementation-plan`](../../docs/04-implementation-plan/).


Domain: Disruption Recovery

Language: python

Phase: future-scope

Status: placeholder-skeleton

## Owns

- RecoveryCase
- RecoveryOption
- CompensationDecision

## DDD Sources

- `docs/02-domains/disruption-recovery.md`

## Language Rationale

Python fits recovery optimization and policy experimentation when this future domain is enabled.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
