# Group Booking

Java/Spring foundation for the Group Booking bounded context. It owns group reservations for 10+ travelers, group fare negotiation references, roster membership, capacity hold confirmation acknowledgement, confirmation, and cancellation/partial cancellation decisions.

## API

- `GET /health`
- `POST /api/v1/group-bookings`
- `PUT /api/v1/group-bookings/{groupBookingId}/members`
- `POST /api/v1/group-bookings/{groupBookingId}/confirm`
- `POST /api/v1/group-bookings/{groupBookingId}/cancel`
- `GET /api/v1/group-bookings/{groupBookingId}`

The domain stores only traveler references and masked document references; raw identity documents must not enter this service.

When `DATABASE_URL` is configured, the owning API persists group bookings and roster members in PostgreSQL, writes domain events to the transactional outbox, and stores HTTP idempotency responses in the same database. A process restart therefore preserves `POST /api/v1/group-bookings`, roster updates and later confirmation through `GET`/`POST` owning endpoints. The in-memory repository is used only when no database is configured.

Status: implemented and deployed (Helm revision 521; see [current status](../../docs/00-current-status.md)).

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `group_booking` on the `core` PostgreSQL instance
  (`services.group-booking.db` and `postgres.instances.core.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

The full contract lives in
[`docs/08-contracts/api/group-booking.md`](../../docs/08-contracts/api/group-booking.md).

## Events

Publishes `GroupBookingCreated`, `GroupMemberAdded`, `GroupBookingConfirmed`
and `GroupBookingCancelled` to `events:group-booking` through the transactional
outbox; see
[`docs/08-contracts/events/group-booking.md`](../../docs/08-contracts/events/group-booking.md).
Consumes `CapacityHoldConfirmed` from `events:capacity-availability`
(`infrastructure/EventSubscriberConfiguration.java`).

## Metrics

Publishes the Java kit's process-level transport series; it owns no business
metric in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Build and test

```bash
cd services/group-booking && mvn -o test
```

Requires JDK 25 (the repo's devcontainer / `maven:3.9-eclipse-temurin-25` image).
