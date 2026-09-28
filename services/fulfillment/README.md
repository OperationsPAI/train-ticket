# fulfillment

Domain: Fulfillment

Language: golang

Phase: phase-1-limited

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- `FulfillmentRecord`, the facts of one traveler on one segment.
- `EvidenceDispute`.
- `BoardingVerified`, `NoShowRecorded` and `FulfillmentCompleted` facts.

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `fulfillment`, on the `core` PostgreSQL instance
  (`services.fulfillment.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- The boarding estate the service boards through is
  `FULFILLMENT_BOARDING_SOURCES`, the readers a release declares in
  `services.fulfillment.env`; a release that declares none keeps `GATE`,
  `STATION`, `PROVIDER`, `CONDUCTOR` and `ADMIN`, the readers the image was
  built with (`internal/boarding`). The release in this repository declares
  the estate there.
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

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
variable are unset. `internal/boarding` covers the estate a release declares and
the vocabulary an unset release keeps, and `internal/application` covers the
source check the boarding command makes.

## DDD Sources

- `docs/02-domains/fulfillment.md`

## Language Rationale

Go keeps event ingestion and station-side fact normalization operationally simple.
