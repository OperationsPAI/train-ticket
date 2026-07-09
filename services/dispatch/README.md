# dispatch

Domain: Dispatch

Language: golang

Phase: phase-1-core

Status: active-dispatch-service

## Owns

- RideRequest
- RideAssignment
- DriverLifecycle
- Eta

## DDD Sources

- `docs/02-domains/dispatch.md`

## Language Rationale

Go fits real-time dispatch APIs, adapter calls, and low-latency state updates.

## Active Slice

REQ-114 implements the REQ-113 activation contract: ride request creation,
assignment, ETA, arrival/start/complete, driver cancellation with automatic
return to matching, user cancellation, no-show recording, persisted snapshots,
and transactional outbox publication for the nine lifecycle facts. Driver-arrived
wait timeout to no-show is intentionally ops-command driven in this wave.

## Service Check

```bash
go test ./...
```
