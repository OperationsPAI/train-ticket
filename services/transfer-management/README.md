# transfer-management

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
