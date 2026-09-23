# Payment

Java service for payment intents, refunds, callback identity and late payment handling.
Remaining work is tracked in [TODO](../../docs/TODO.md).

## Owns

- `PaymentIntent` creation, authorization, capture, failure, cancellation, expiration, captured/refunded balance, and business idempotency keys.
- `ChannelCallbackRecord` audit and channel callback duplicate detection.
- `Refund` request, submit, settlement, retryable failure, and final/manual-review failure lifecycle for original-route refunds.
- `LatePaymentCase` facts for captures that arrive after cancellation or expiration.
- Domain events including `PaymentIntentCreated`, `PaymentAuthorized`, `PaymentCaptured`, `PaymentFailed`, `PaymentIntentExpired`, `RefundRequested`, `RefundSettled`, `RefundFailed`, `ChannelCallbackReceived`, duplicate callback facts, and `LatePaymentDetected`.

## Boundary Rules

Payment publishes funds facts only. It must not confirm orders, issue entitlements, mutate capacity, send notifications directly, or make post-sales refund eligibility decisions. A late `PaymentCaptured` after order cancellation/intent expiration opens a `LatePaymentCase`; it does not recover an order or ticket.

## DDD Sources

- `docs/02-domains/payment.md`
- `docs/03-ddd-final/phase-1-contract.md`

## Language Rationale

Java is a strong default for money state machines, idempotency, and audit-heavy flows.

## Validation

```bash
mvn test
```

## Recorded wallet compensation tests

`CompensationRecordedEventLiveTest` uses PostgreSQL repositories and the actual inbound event filter in an isolated schema.
`src/test/resources/wallet-compensation-recorded.json` contains unchanged revision 274 approval and applied envelopes for case `psc-01a0ceba-9bb5-7fbe-ac91-2168e09ae5ad`, together with its recorded source case and payment snapshot.
The approved wallet credit is CNY 886 minor units and the original captured payment is CNY 2886 minor units with no refund.
The test checks the complete payment row including version, zero refund rows and zero outbox rows after duplicate delivery and handler reconstruction.
The historical fixture also exercises an approval containing a refund amount with sufficient remaining payment balance, and the absence of a local payment snapshot.
Its applied envelope has no `caseType`; the persisted source case establishes `COMPENSATION`, and the envelope remains unchanged.

Run from this module with Java 25 and a disposable PostgreSQL database.
The test creates and removes its own schema, limits statements to five seconds and lock waits to two seconds, and uses no Redis subscriber or outbox relay.
The HTTP channel client points to a closed local port so an accidental channel request cannot reach a deployed payment channel.

```bash
TEST_DATABASE_URL='jdbc:postgresql://127.0.0.1:15432/postgres?user=postgres' mvn -Dtest=CompensationRecordedEventLiveTest test
```

Verified on 2026-09-23: three tests passed with no failures or skips.
The recorded events came from Redis streams and the snapshots from primary key PostgreSQL reads.
