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
- Wayfinding defaults the release ships live in
  `internal/config/wayfinding.json`, embedded at build time. A value of `0`
  states the allowance an unweighed station node carries; `0` would leave
  the node's `accessTimeMinutes` absent.

A station node created without an `accessTimeMinutes` is answered with the
allowance the release ships, so a consumer that plans against the node reads a
weight rather than an absence. A node that declared its own weight keeps it.

## HTTP surface

- `POST/GET /api/v1/places` and `GET /api/v1/places/{placeId}`.
- `POST /api/v1/transport-nodes` and `GET /api/v1/transport-nodes/{nodeId}`.

The full contract lives in
[`docs/08-contracts/api/place-network.md`](../../docs/08-contracts/api/place-network.md);
the node reads there carry the access-time semantics these handlers apply.

## Events

Publishes `PlaceRegistered`, `TransportNodeRegistered` and node-update facts to
`events:place-network` (`internal/adapters/messaging/stream_config.go`); see
[`docs/08-contracts/events/place-network.md`](../../docs/08-contracts/events/place-network.md).
It consumes no inbound event stream.

## Settings

`internal/config/wayfinding.json` travels inside the image and carries the wayfinding the release ships for
nodes the operator did not weigh. `stationAccessTimeMinutes` is the station
allowance in whole minutes; `0` leaves the field absent.

## Metrics

This service owns no service-specific business metric in the
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/place-network && go test ./...
```

The allowance is in whole minutes on the same clock the request's weight uses,
so a consumer that reads both cannot mix units.

## DDD Sources

- `docs/02-domains/place-network.md`

The allowance exists because absence and zero are different facts: a station
nobody measured is not a station with no wayfinding, and every consumer that
plans a connection would otherwise have to guess which one it is holding.

## Language Rationale

Go fits read-heavy master-data APIs and simple operational lookup paths.

## Active Slice

REQ-004 adds small domain primitives for `Place`, `TransportNode`, and
`ProviderPlaceMapping`. The primitives validate canonical names, lifecycle
statuses, WGS84 coordinates, serving transport modes, provider-code confidence,
and explicit low-confidence/conflict gap reasons so provider raw codes do not
leak into downstream core contracts.
