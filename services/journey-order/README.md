# journey-order

Domain: Journey Order

Language: Java

Phase: phase-1-domain-foundation

Status: REQ-010 domain foundation

Work slice: REQ-010-Journey-Order-domain-foundation

## Owns

- `JourneyOrder` commercial order aggregate
- `OrderItem` commercial line summaries and traveler/segment bindings
- `TravelerRef` snapshots and masked document references
- `MonetarySummary` invariants for order totals, discounts, fees, taxes, and cancelled commercial value
- `OrderTimeline` / `TimelineFact` audit anchors for downstream visibility
- Confirmation-condition summary facts required before commercial confirmation

## Explicitly Does Not Own

- Inventory or capacity holds
- Payment-channel state, payment capture, refunds, or callbacks
- Entitlement credentials, ticket issuance, or provider PNR/status
- Fare calculation, refund rules, or supplier calls

## DDD Sources

- `docs/02-domains/journey-order.md`

## Domain Behavior Covered

The current Java model creates a `JourneyOrder` from a valid Offer snapshot and traveler snapshots, enforces order item binding and monetary summary invariants, records timeline facts, publishes domain events (`JourneyOrderCreated`, `JourneyOrderPendingPayment`, `JourneyOrderConfirmed`, cancellation and post-sales adjustment facts), and guards confirmation so `PaymentCaptured` only moves the order into `CONFIRMING` until accepted booking, capacity, payment, entitlement, and risk facts are represented.

## Check

```bash
mvn test
```
