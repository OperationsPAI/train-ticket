# booking-orchestration

Domain: Booking Orchestration

Language: Java

Phase: phase-1-core

Status: REQ-011 domain foundation

Work slice: REQ-011

## Owns

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
mvn test
```
