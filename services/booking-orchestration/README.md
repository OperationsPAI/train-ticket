# booking-orchestration

Domain: Booking Orchestration

Language: java

Phase: phase-1-core

Status: skeleton

## Owns

- BookingSaga
- SegmentBooking
- ProviderReservation mapping

## DDD Sources

- `docs/02-domains/booking-orchestration.md`

## Language Rationale

Java gives the saga layer explicit state transitions and stable integration testing options.

## Skeleton Check

```bash
mvn test
```
