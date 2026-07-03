# payment

Domain: Payment

Language: java

Phase: phase-1-domain-foundation

Status: REQ-012 domain foundation

Work Package: REQ-012-Payment-domain-foundation

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
