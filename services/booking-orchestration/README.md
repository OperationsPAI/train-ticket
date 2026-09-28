# booking-orchestration

Domain: Booking Orchestration

Language: Java

Phase: phase-1-core

Status: REQ-011 domain foundation

Work slice: REQ-011

## Owns

`POST /api/v1/internal/change-compensations/{bookingId}` cancels a recorded replacement after Post Sales persists capacity rejection evidence.
It validates the case, order and booking scope, preserves original reservations and emits the replacement cancellation once.
Issued replacements require ticket void confirmation.
`CapacityCompensationLiveTest` verifies a recorded full class rejection and concurrent cancellation using PostgreSQL and actual Seat Assignment.

- `SegmentBooking` aggregate root — segment-level booking execution lifecycle (REQUESTED → HOLDING → CONFIRMED → TICKETED, with cancellation and failure paths)
- `BookingSaga` saga orchestrator — persisted step coordination with idempotency keys, timeouts, retry limits, and compensation actions for each step
- `ReservationRequestLog` — idempotent audit log for external provider reservation requests (ReserveSegment, ConfirmReservation, CancelReservation)
- `CompensationCase` — controlled compensation for failed saga steps, tracking provider cancellation, hold release, and refund requests

## Key Invariants

1. One `SegmentBooking` per `JourneyOrderId + SegmentRef + TravelerRef + bookingPurpose`; provider confirmation maps idempotently to platform bookingId.
2. `ProviderReservationTimeout` must not be treated as failure — must QueryStatus first, never blindly retry create operations.
3. Saga must be persisted; every step requires an idempotency key, timeout, retry limit, and compensation action.

## Explicitly Does Not Own

- Journey Order commercial totals or order aggregate state.
- Capacity inventory conflict algorithms or hold internals.
- Payment channels, captures, refunds or channel callbacks.
- Entitlement credentials, ticket display secrets or validation.
- Raw provider status codes, payload parsing, signatures, retries or supplier API calls.

## Domain Events

- `BookingSagaStarted`, `BookingSagaCompleted`, `BookingSagaFailed`
- `SegmentReservationRequested`, `SegmentReservationConfirmed`, `SegmentReservationFailed`, `SegmentTicketed`, `SegmentBookingCancelled`

## DDD Sources

- `docs/02-domains/booking-orchestration.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Checks

```bash
cd services/booking-orchestration && mvn -o test
```

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `booking_orchestration` on the `hot` PostgreSQL instance
  (`services.booking-orchestration.db`, `services.booking-orchestration.pgInstance`
  and `postgres.instances.hot.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

## HTTP surface

- `/api/v1/internal/booking-sagas`: create, read, `by-order/{orderId}`,
  `{sagaId}/request-reservation`, `{sagaId}/mark-ticketed`.
- `/api/v1/internal/change-reservations` and `{bookingId}/cancel`.
- `/api/v1/internal/change-compensations/{bookingId}`.
- `GET /api/v1/internal/segment-bookings/{segmentBookingId}`.

The full contract lives in
[`docs/08-contracts/api/booking-orchestration.md`](../../docs/08-contracts/api/booking-orchestration.md).

## Events

Publishes to `events:booking-orchestration`; the event types and consumers are
in [`docs/08-contracts/events/booking-orchestration.md`](../../docs/08-contracts/events/booking-orchestration.md).
Consumes `events:capacity-availability`, `events:journey-order`,
`events:payment`, `events:provider-integration`,
`events:entitlement-ticketing`, `events:risk-compliance`,
`events:seat-assignment` and `events:invoicing`
(`adapters/messaging/RedisEventBusInitializer.java`).

## Metrics

Emits `train_ticket.booking.saga.active|transitions|oldest_age|step_due_age`
and the `reservation`, `payment` and `ticket` stages of
`train_ticket.order.transitions`
(`src/main/java/com/trainticket/bookingorchestration/observability/`); the
contract is in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

Java tests require JDK 25 (the repo's devcontainer /
`maven:3.9-eclipse-temurin-25` image). Recorded tests need a live PostgreSQL
(and, for some, Redis) and skip when `TEST_DATABASE_URL` / `TEST_REDIS_URL` are
unset.

## Accepted supply references

Initial reservation HTTP requests resolve the accepted Order, Offer, fare quote and itinerary.
The service validates account, traveler set, segment and departure facts before persisting `classRef`, `offerItemRef`, `scheduledServiceRef` and `serviceDate`.
Change reservations capture these fields from the approved replacement offer.
The booking detail API and reservation events expose the recorded supply fields.
Concurrent requests for the same business key return one stored booking identifier, which callers must use for subsequent operations.

`ReservationFactsLiveTest` verifies real upstream references and concurrent PostgreSQL persistence.
Its environment variables are `TEST_RESERVATION_FACTS_SOURCE`, `TEST_DATABASE_URL` and `TEST_LIVE_BASE_URL_TEMPLATE`.
This integration must be deployed with inventory reconciliation so historical `standard` occupancy remains counted.
