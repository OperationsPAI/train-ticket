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

## Recorded wallet compensation tests

`CompensationRecordedEventLiveTest` replays actual approval and applied envelopes through `OrderManagementService` with PostgreSQL persistence and transactions in an isolated schema.
`src/test/resources/wallet-compensation-recorded.json` contains revision 274 case `psc-01a0ceba-9bb5-7fbe-ac91-2168e09ae5ad` and order `ord-01a0ceba-74bf-7000-a01c-6e5c316e1b84` at version 7.
The source order is confirmed, has two travelers and has no cancelled commercial items.
The test compares the entire persisted row after each event, including items, amounts, timeline, state, version and update timestamp, and requires an empty outbox.
Duplicate delivery and handler reconstruction preserve the same state.
Both fixtures retain the original event identifiers and payloads, including the recorded `caseType=COMPENSATION`.
The historical order is already adjusted; the revision 274 fixture covers a preserved confirmed order.

Run from this module with Java 25 and a disposable PostgreSQL database.
The test creates and removes its own schema and uses no Redis subscriber or outbox relay.
Statement and lock timeouts are five and two seconds respectively.

```bash
TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:15432/postgres?user=postgres' mvn -Dtest=CompensationRecordedEventLiveTest test
```

Verified on 2026-09-23: two tests passed with no failures or skips.
The recorded events came from Redis streams and the snapshots from primary key PostgreSQL reads.
