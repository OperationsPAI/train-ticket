# finance-settlement

Domain: Finance Settlement

Language: java

The service records revenue, fees, reconciliation and benefit costs through PostgreSQL repositories and event processing.

Daily reconciliation and supplier settlements run automatically for the latest closed UTC period. A persisted schedule-run row leases each logical period to one replica, records attempts and stores the resulting reconciliation batch or supplier settlement IDs. A failed or expired lease is retryable; a successful run is not recalculated after restart.

The supplier schedule defaults to daily, a 5% platform commission and zero withholding. Configure `finance.supplier-settlement.frequency` (`DAILY`, `WEEKLY`, `MONTHLY`), `finance.supplier-settlement.commission-pct`, `finance.supplier-settlement.withholding-pct`, and the scheduler timing properties when another policy is required. Only ordinary fare revenue carrying a supplier reference distinct from the order ID is automatically settled; missing supplier attribution remains visible instead of being paid to an order identifier.

Schedule evidence is available from `GET /api/v1/settlement-schedule-runs` and `GET /api/v1/settlement-schedule-runs/{jobKey}`.

## Owns

- RevenueRecognition
- Reconciliation
- Invoice
- SettlementView

## DDD Sources

- `docs/02-domains/finance-settlement.md`

## Language Rationale

Java is appropriate for financial ledgers, reconciliation workflows, and audit consistency.

## Validation

```bash
mvn test
```

## Recorded wallet compensation tests

`CompensationRecordedEventLiveTest` consumes unchanged revision 274 events for case `psc-01a0ceba-9bb5-7fbe-ac91-2168e09ae5ad` using PostgreSQL repositories, transaction advice and a transactional outbox in isolated schemas.
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
