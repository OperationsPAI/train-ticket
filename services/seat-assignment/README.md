# Seat Assignment

Go implementation of CRH380A train layout modelling, preference-based seat assignment, inventory holds, confirmation, release lifecycle, HTTP API, Postgres persistence, and Redis event integration.

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `seat_assignment` on the `read` PostgreSQL instance
  (`services.seat-assignment.db`, `services.seat-assignment.pgInstance` and
  `postgres.instances.read.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/ready`,
`/metadata`.

## HTTP surface

- `/api/v1/seat-assignments` create plus `{id}` delete and `{id}/confirm`;
  `GET /api/v1/seat-assignments/{segmentRef}/{departureDate}/availability`.
- `/api/v1/seat-maps` create, list, read, `{id}/publish`, `{id}/retire`,
  `{id}/seat-units/{seatUnitRef}/mark-unavailable|reopen`.
- Internal: `POST /api/v1/internal/seat-allocations`,
  `GET /api/v1/internal/capacity-facts`,
  `GET /api/v1/seat-allocations` and `{id}`.

The full contract lives in
[`docs/08-contracts/api/seat-assignment.md`](../../docs/08-contracts/api/seat-assignment.md).

## Events

Publishes `SeatMapBuilt`, `SeatAllocated`, `SeatAllocationConfirmed`,
`SeatAllocationReleased` and the standing substitution fact to
`events:seat-assignment`; see
[`docs/08-contracts/events/seat-assignment.md`](../../docs/08-contracts/events/seat-assignment.md).
Consumes `events:booking-orchestration`, `events:entitlement-ticketing`,
`events:post-sales` and `events:capacity-availability`
(`internal/adapters/messaging/subscriber.go`).

## Metrics

Emits `train_ticket.seat.allocations` (`internal/adapters/observability/`);
the contract is in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Supply facts and standing quota

`GET /api/v1/internal/capacity-facts` exposes a published composition and active allocation references for a scheduled service, date and class.
The query is read only and includes historical allocations.
Standing has no concrete SeatUnit; its versioned admission quota is ten percent of assignable second class seats, rounded down.
The quota is checked under the service allocation lock and recorded on allocations and events.
Allocation replay validates its class against the composition recorded on the original allocation.
A later composition retirement or replacement does not change an existing allocation's identity or seat reference.
New allocations retain the requested class, issue purpose and preferences so accepted standing substitutions also replay without consuming another quota slot.

See [Standing capacity policy](../../docs/08-contracts/standing-capacity-policy.md) and [HTTP API](../../docs/08-contracts/api/seat-assignment.md).

## Verification

Run `go test ./...` from this directory.
Set `TEST_DATABASE_URL`, `TEST_CAPACITY_SEAT_FACTS` and `TEST_STANDING_SOURCE` to run the recorded PostgreSQL supply and standing tests.
The tests create isolated schemas and remove those schemas after verification.
