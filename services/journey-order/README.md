# Journey Order

Java service for commercial orders, accepted Offer references, financial summaries, confirmation conditions, PostgreSQL persistence and Redis event processing.

Last updated: 2026-09-28.

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
cd services/journey-order && mvn -o test
```

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `journey_order` on the `hot` PostgreSQL instance
  (`services.journey-order.db`, `services.journey-order.pgInstance` and
  `postgres.instances.hot.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

## HTTP surface

- `/api/v1/journey-orders`: create, list, read, `{orderId}/cancel`,
  `{orderId}/apply-change`.
- `GET /api/v1/internal/journey-orders/{orderId}/purchase-source`.

The full contract lives in
[`docs/08-contracts/api/journey-order.md`](../../docs/08-contracts/api/journey-order.md).

## Events

Publishes `JourneyOrderCreated`, `JourneyOrderPendingPayment`,
`JourneyOrderPaymentRecorded`, `JourneyOrderConfirmed`,
`JourneyOrderCancelled` and post-sales adjustment facts to
`events:journey-order`; see
[`docs/08-contracts/events/journey-order.md`](../../docs/08-contracts/events/journey-order.md).
Consumes `events:payment`, `events:post-sales`, `events:risk-compliance`,
`events:account`, `events:entitlement-ticketing`,
`events:ancillary-service`, `events:identity-verification` and
`events:transfer-management`
(`adapters/messaging/RedisJourneyOrderSubscriptions.java`).

## Risk scenarios

An order confirms once a risk assessment clears it, and `events:risk-compliance`
carries risk-compliance's assessment for four subjects: the order, the payment
intent, the post-sales case and the account. Only the order's own assessment
resolves to an order here, so the handler acts on the scenarios the release names
in `JOURNEY_ORDER_RISK_SCENARIOS` (`services.journey-order.env` in
`deploy/helm/train-ticket/values.yaml`); a scenario the list does not name is
acknowledged without changing an order, and the image's own list names every
scenario the platform emits today.

## Metrics

Emits the `order` stage of `train_ticket.order.transitions`
(`src/main/java/com/trainticket/journeyorder/observability/`); the contract is in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

Java tests require JDK 25 (the repo's devcontainer /
`maven:3.9-eclipse-temurin-25` image).

`PurchaseSourceLiveTest` loads a recorded changed order into an isolated PostgreSQL schema and exercises the HTTP endpoint.
It verifies the original Offer, repeated reads, unknown order response, unchanged snapshot version and no outbox effects.
Set `TEST_PURCHASE_SOURCE_ORDER` to a recorded `{id, version, data}` snapshot, `TEST_DATABASE_URL` to a JDBC PostgreSQL URL, `TEST_DATABASE_URI` to its PostgreSQL URI and `TEST_REDIS_URL` to a disposable Redis instance.
The test requires a source whose current Offer differs from its recorded purchase Offer.

## Recorded wallet compensation tests

`CompensationRecordedEventLiveTest` replays actual approval and applied envelopes through `OrderManagementService` with PostgreSQL persistence and transactions in an isolated schema.
`src/test/resources/wallet-compensation-recorded.json` contains recorded case `psc-01a0ceba-9bb5-7fbe-ac91-2168e09ae5ad` and order `ord-01a0ceba-74bf-7000-a01c-6e5c316e1b84` at version 7.
The source order is confirmed, has two travelers and has no cancelled commercial items.
The test compares the entire persisted row after each event, including items, amounts, timeline, state, version and update timestamp, and requires an empty outbox.
Duplicate delivery and handler reconstruction preserve the same state.
Both fixtures retain the original event identifiers and payloads, including the recorded `caseType=COMPENSATION`.
The historical order is already adjusted; the recorded fixture covers a preserved confirmed order.

Run from this module with Java 25 and a disposable PostgreSQL database.
The test creates and removes its own schema and uses no Redis subscriber or outbox relay.
Statement and lock timeouts are five and two seconds respectively.

```bash
TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:15432/postgres?user=postgres' mvn -Dtest=CompensationRecordedEventLiveTest test
```

Verified on 2026-09-23: two tests passed with no failures or skips.
The recorded events came from Redis streams and the snapshots from primary key PostgreSQL reads.
