# provider-integration

Domain: Provider Integration

Language: golang

Phase: phase-1-support

Status: skeleton

## Owns

- adapter protocol
- signature verification
- raw archive
- external status mapping

## DDD Sources

- `docs/02-domains/provider-integration.md`

## Language Rationale

Go fits protocol adapters, webhooks, retry loops, and small deployable edge services.

## Skeleton Check

```bash
go test ./...
```
