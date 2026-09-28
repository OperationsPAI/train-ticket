# dispatch

Domain: Dispatch

Language: golang

Phase: phase-1-core

Status: active-dispatch-service, implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- RideRequest
- RideAssignment
- DriverLifecycle
- Eta

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `dispatch` on the `core` PostgreSQL instance
  (`services.dispatch.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## HTTP surface

`/api/v1/ride-requests`: create, list, read, `assign`, `eta`,
`driver-arrived`, `start`, `complete`, `driver-cancel`, `user-cancel`,
`no-show`.

The full contract lives in
[`docs/08-contracts/api/dispatch.md`](../../docs/08-contracts/api/dispatch.md).

## Events

Publishes the ride lifecycle facts (`DispatchRequested`, `DriverAssigned`,
`DriverEtaUpdated`, `DriverArrived`, `RideStarted`, `RideEnded`,
`DriverCancelled`, `DispatchUserCancelled`, `DispatchNoShowRecorded`,
`DispatchFailed`) to `events:dispatch`
(`internal/domain/events.go`, producer `dispatch`); see
[`docs/08-contracts/events/dispatch.md`](../../docs/08-contracts/events/dispatch.md).
It consumes no inbound event stream.

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

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
cd services/dispatch && go test ./...
```
