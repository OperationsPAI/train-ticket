# booking-orchestration

Domain: Booking Orchestration

Language: java

Phase: phase-1-core

Status: REQ-011 domain foundation

## Owns

- `BookingSaga` progress across reservation, payment, confirmation, ticketing, compensation and manual-review steps.
- `SegmentBooking` lifecycle from reservation requested through confirmed, failed, ticketed and cancelled states.
- Normalized Provider Integration facts such as `ProviderReservationConfirmed` mapped to internal `SegmentReservationConfirmed` facts.

## Does Not Own

- Journey Order commercial totals or order aggregate state.
- Capacity inventory conflict algorithms or hold internals.
- Payment channels, captures, refunds or channel callbacks.
- Entitlement credentials, ticket display secrets or validation.
- Raw provider status codes, payload parsing, signatures, retries or supplier API calls.

## Coordination Facts

The domain emits events for consumers instead of writing into other contexts directly:

- Journey Order: `SegmentReservationRequested`, `SegmentReservationConfirmed`, `SegmentReservationFailed`, `BookingSagaFailed`.
- Capacity: hold, confirm and release intent is represented as saga steps and segment facts.
- Payment: payment wait/progress is represented by saga status and coordination events only.
- Entitlement: `SegmentTicketed` records the result of ticketing after Entitlement issues a credential.
- Provider Integration: late provider confirmation after cancellation emits a cancellation-required hook for reconciliation.

## DDD Sources

- `docs/02-domains/booking-orchestration.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Language Rationale

Java gives the saga layer explicit state transitions, immutable coordination facts and stable integration testing options.

## Checks

```bash
mvn test
```
