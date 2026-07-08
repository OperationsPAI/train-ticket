# dispatch

> **Status — future-scope skeleton**
>
> This service is **not deployed**, is **not part of the current 23-service set**,
> and remains **future-scope** until activated by the roadmap in
> [`docs/04-implementation-plan`](../../docs/04-implementation-plan/).


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
