# place-network

Domain: Place & Network

Language: golang

Phase: phase-1-core

Status: REQ-004 master-data primitives, implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md))

## Owns

- Place
- TransportNode
- ProviderPlaceMapping

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `place_network` on the `read` PostgreSQL instance
  (`services.place-network.db`, `services.place-network.pgInstance` and
  `postgres.instances.read.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## HTTP surface

- `POST/GET /api/v1/places` and `GET /api/v1/places/{placeId}`.
- `POST /api/v1/transport-nodes` and `GET /api/v1/transport-nodes/{nodeId}`.

The full contract lives in
[`docs/08-contracts/api/place-network.md`](../../docs/08-contracts/api/place-network.md).

## Events

Publishes `PlaceRegistered`, `TransportNodeRegistered` and node-update facts to
`events:place-network` (`internal/adapters/messaging/stream_config.go`); see
[`docs/08-contracts/events/place-network.md`](../../docs/08-contracts/events/place-network.md).
It consumes no inbound event stream.

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/place-network && go test ./...
```

## DDD Sources

- `docs/02-domains/place-network.md`

## Language Rationale

Go fits read-heavy master-data APIs and simple operational lookup paths.

## Active Slice

REQ-004 adds small domain primitives for `Place`, `TransportNode`, and
`ProviderPlaceMapping`. The primitives validate canonical names, lifecycle
statuses, WGS84 coordinates, serving transport modes, provider-code confidence,
and explicit low-confidence/conflict gap reasons so provider raw codes do not
leak into downstream core contracts.
