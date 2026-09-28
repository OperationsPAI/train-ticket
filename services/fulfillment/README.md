# fulfillment

Domain: Fulfillment

Language: golang

Phase: phase-1-limited

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- FulfillmentRecord
- EvidenceDispute
- BoardingVerified / NoShowRecorded / FulfillmentCompleted facts

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `fulfillment` on the `core` PostgreSQL instance
  (`services.fulfillment.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## Settings

The release's values live in `config/usage.properties`, which the image carries
at `/app/config` and `internal/config` reads once at startup. A deployment that
mounts its own document names it in `FULFILLMENT_USAGE_SETTINGS`. A document
the service cannot read, or a key it does not know, is fatal at startup.

| Key | Meaning |
| --- | --- |
| `usage.minimumSegmentSeconds` | The shortest time a segment this release takes from its boarding to its arrival, stated in the half hour the timetables are planned in; `0` states no minimum. |

## HTTP surface

- `POST /api/v1/fulfillment-records/boarding`,
  `POST /api/v1/fulfillment-records/completions`,
  `POST /api/v1/fulfillment-records/no-show`,
  `GET /api/v1/fulfillment-records/{id}`.
- `POST /api/v1/segment-status`.

The full contract lives in
[`docs/08-contracts/api/fulfillment.md`](../../docs/08-contracts/api/fulfillment.md).

## Events

Publishes `BoardingVerified`, `NoShowRecorded`, `FulfillmentCompleted`,
`EvidenceDisputeOpened` and `EvidenceDisputeResolved` to `events:fulfillment`;
see [`docs/08-contracts/events/fulfillment.md`](../../docs/08-contracts/events/fulfillment.md).
Consumes `events:entitlement-ticketing`, `events:booking-orchestration`,
`events:ancillary-service` and `events:dispatch`
(`internal/adapters/messaging/redis.go`).

## Metrics

Emits the `validation` stage of `train_ticket.ticketing.transitions`
(`internal/adapters/observability/ticketing.go`); the contract is in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/fulfillment && go test ./...
```

Live PostgreSQL tests skip when `TEST_DATABASE_URL` and their recorded fixture
variable are unset.

## DDD Sources

- `docs/02-domains/fulfillment.md`

## Language Rationale

Go keeps event ingestion and station-side fact normalization operationally simple.
