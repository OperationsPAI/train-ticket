# supplier-catalog

Domain: Supplier Catalog

Language: golang

Phase: phase-1-support

Status: skeleton

## Owns

- Supplier
- Carrier
- Contract
- ProductCapability
- ExternalCode

## DDD Sources

- `docs/02-domains/supplier-catalog.md`

## Language Rationale

Go keeps supplier capability lookup compact and separate from provider runtime health.

## Skeleton Check

```bash
go test ./...
```
