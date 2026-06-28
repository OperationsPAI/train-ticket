# journey-order

Domain: Journey Order

Language: java

Phase: phase-1-core

Status: skeleton

## Owns

- JourneyOrder
- OrderItem
- MonetarySummary
- OrderTimeline

## DDD Sources

- `docs/02-domains/journey-order.md`

## Language Rationale

Java is conservative for central transactional aggregates and long-lived domain models.

## Skeleton Check

```bash
mvn test
```
