# Journey Order

Java service for commercial orders, accepted Offer references, financial summaries, confirmation conditions, PostgreSQL persistence and Redis event processing.

Last updated: 2026-09-23.

## Accepted purchase source

`GET /api/v1/internal/journey-orders/{orderId}/purchase-source` reads the original accepted Offer from the order's persisted creation timeline fact.
It returns the order, account, Offer, timeline fact identifier and acceptance timestamp without changing business state.
The current order Offer changes after an applied ticket change, while this initial purchase source remains stable.
See the [HTTP API](../../docs/08-contracts/api/journey-order.md) for fields and failure conditions.

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

`PurchaseSourceLiveTest` loads a recorded changed order into an isolated PostgreSQL schema and exercises the HTTP endpoint.
It verifies the original Offer, repeated reads, unknown order response, unchanged snapshot version and no outbox effects.
Set `TEST_PURCHASE_SOURCE_ORDER` to a recorded `{id, version, data}` snapshot, `TEST_DATABASE_URL` to a JDBC PostgreSQL URL, `TEST_DATABASE_URI` to its PostgreSQL URI and `TEST_REDIS_URL` to a disposable Redis instance.
The test requires a source whose current Offer differs from its recorded purchase Offer.
