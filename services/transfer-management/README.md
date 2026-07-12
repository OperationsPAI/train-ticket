# transfer-management

> **Status — future-scope skeleton**
>
> This service is **not deployed**, is **not part of the current 23-service set**,
> and remains **future-scope** until activated by the roadmap in
> [`docs/04-implementation-plan`](../../docs/04-implementation-plan/).


Domain: Transfer Management

Language: python

Phase: future-scope

Status: placeholder-skeleton

## Owns

- ConnectionContract
- MctVersion
- ProtectedConnection

## DDD Sources

- `docs/02-domains/transfer-management.md`

## Language Rationale

Python fits connection-risk calculation and later protected-transfer recovery planning.

## Skeleton Check

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
