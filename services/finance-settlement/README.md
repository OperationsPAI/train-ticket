# finance-settlement

Domain: Finance Settlement

Language: java

The service records revenue, fees, reconciliation and benefit costs through PostgreSQL repositories and event processing.

Daily reconciliation and supplier settlements run automatically for the latest closed UTC period. A persisted schedule-run row leases each logical period to one replica, records attempts and stores the resulting reconciliation batch or supplier settlement IDs. A failed or expired lease is retryable; a successful run is not recalculated after restart.

The supplier schedule defaults to daily, a 5% platform commission and zero withholding. Configure `finance.supplier-settlement.frequency` (`DAILY`, `WEEKLY`, `MONTHLY`), `finance.supplier-settlement.commission-pct`, `finance.supplier-settlement.withholding-pct`, and the scheduler timing properties when another policy is required. Only ordinary fare revenue carrying a supplier reference distinct from the order ID is automatically settled; missing supplier attribution remains visible instead of being paid to an order identifier.

Schedule evidence is available from `GET /api/v1/settlement-schedule-runs` and `GET /api/v1/settlement-schedule-runs/{jobKey}`.

Applied post-sales refunds are matched to the revenue recognition whose source event appears in each Payment intent's authoritative captured facts. A case may span the original payment and multiple fare-difference payments, and one payment may have multiple settled refunds. Finance requires the case refund sum to equal the approved total, aggregates new and previously recorded reversals per payment, and rejects amounts beyond Payment's persisted refunded total or captured amount. Refund IDs are durable reversal identities: an exact replay is a no-op, while reusing one with another amount is rejected.

## Owns

- RevenueRecognition
- Reconciliation
- Invoice
- SettlementView

## DDD Sources

- `docs/02-domains/finance-settlement.md`

## Runtime

- HTTP port `8080` (`containerPort`/`PORT` in the chart).
- Database `finance_settlement` on the `txn` PostgreSQL instance
  (`services.finance-settlement.db`,
  `services.finance-settlement.pgInstance` and
  `postgres.instances.txn.databases` in
  `deploy/helm/train-ticket/values.yaml`).
- Liveness `/healthz`, readiness `/readyz`; also `/health`, `/live`,
`/livez`, `/ready`, `/metadata`.

## HTTP surface

- `GET /api/v1/revenue-recognitions/{id}`,
  `GET /api/v1/order-revenue-bases/{orderId}`,
  `GET /api/v1/reconciliation-cases` and `{id}`,
  `GET /api/v1/invoices/{id}`, `POST /api/v1/invoices`.
- `GET /api/v1/channel-statements` and `{id}`,
  `GET /api/v1/settlements/daily/{date}`,
  `GET /api/v1/settlements/suppliers/{supplierId}/period`.
- Daily reconciliation: `GET /api/v1/settlements/reconciliation/{batchId}` and
  `POST /api/v1/settlements/reconciliation/{date}/run` (run/rerun a closed
  period's reconciliation, including the refund side).
- `GET/POST /api/v1/settlement-schedule-runs` and `{jobKey}`,
  `GET /api/v1/benefit-costs`, `GET /api/v1/ancillary-financial-facts`, and
  `POST /api/v1/internal/change-financial-reconciliations/{caseId}`.

The full contract lives in
[`docs/08-contracts/api/finance-settlement.md`](../../docs/08-contracts/api/finance-settlement.md).

## Events

Publishes `RevenueRecognized`, `RevenueRecognitionReversed`,
`ReconciliationCaseOpened`, `ReconciliationCaseResolved`,
`ReconciliationCompleted`, `InvoiceGenerated` and `SettlementViewRebuilt` to
`events:finance-settlement`; see
[`docs/08-contracts/events/finance-settlement-events.md`](../../docs/08-contracts/events/finance-settlement-events.md).
Consumes `events:payment`, `events:provider-integration`,
`events:booking-orchestration`, `events:post-sales`,
`events:wallet-promotion`, `events:payment-channel` and
`events:ancillary-service`
(`adapters/messaging/RedisMessagingProperties.java`).

## Metrics

Emits `train_ticket.scheduled_job.success_age` for its daily reconciliation and
supplier settlement, with the `scheduled_job` label
(`adapters/observability/OtelScheduledJobMetrics.java`); the contract is in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).

## Language Rationale

Java is appropriate for financial ledgers, reconciliation workflows, and audit consistency.

## Validation

```bash
cd services/finance-settlement && mvn -o test
```

Requires JDK 25 (the repo's devcontainer / `maven:3.9-eclipse-temurin-25`
image). Recorded tests use `TEST_DATABASE_URL` and skip when it is unset.

## Recorded wallet compensation tests

`CompensationRecordedEventLiveTest` consumes unchanged recorded events for case `psc-01a0ceba-9bb5-7fbe-ac91-2168e09ae5ad` using PostgreSQL repositories, transaction advice and a transactional outbox in isolated schemas.
`src/test/resources/wallet-compensation-recorded.json` contains the source case, original `PaymentCaptured`, `PostSalesApproved`, `PostSalesApplied` and wallet `BenefitIssued` envelopes.
Approval uses `approvedActions.compensation.method=WALLET_CREDIT` and has no refund instruction.
The tests establish original revenue from the recorded capture, then verify unchanged revenue, fees, reconciliation, capture references and outbox through duplicate delivery and handler reconstruction.
Applied delivery before approval succeeds, and approval creates no refund projection.
`BenefitIssued` creates exactly one `benefit_cost_entries` row with `issuance_source=POST_SALES_COMP`, the recorded account, case and benefit identifiers, and CNY 886 minor units.
The original ticket revenue remains CNY 2886 minor units with no reversal.
PostgreSQL timestamp comparisons allow its microsecond precision while amount comparisons remain exact.

Run from this module with Java 25 and a disposable PostgreSQL database.
The tests create and remove their schemas, limit statements to five seconds and lock waits to two seconds, and start no Redis subscriber, outbox relay or remote business requests.

```bash
TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:15432/postgres?user=postgres' mvn -Dtest=CompensationRecordedEventLiveTest test
```

The historical approval fixture for case `psc-01a0ccc0-eb97-7fde-aee0-8103704e998a` contains `decisionKind=COMPENSATION` together with `approvedActions.refund.amount`.
The handler ignores compensation approvals before reading any refund instruction, and the recorded test requires zero approved refund projections.
Compensation results preserve revenue, and wallet events supply the compensation cost.
The recorded PostgreSQL suite passes all five tests in 1.687 seconds, including approval replay with a carried refund instruction.
Source events were read from Redis streams and original capture history and case data from primary key PostgreSQL reads.
