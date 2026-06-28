# dispatch

Domain: Dispatch

Language: golang

Phase: future-scope

Status: placeholder-skeleton

## Owns

- RideRequest
- RideAssignment
- DriverLifecycle
- Eta

## DDD Sources

- `docs/02-domains/dispatch.md`

## Language Rationale

Go fits real-time dispatch APIs, adapter calls, and low-latency state updates.

## Skeleton Check

```bash
go test ./...
```
