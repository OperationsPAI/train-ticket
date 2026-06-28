# payment

Domain: Payment

Language: java

Phase: phase-1-core

Status: skeleton

## Owns

- PaymentIntent
- Refund
- CallbackRecord
- IdempotencyKey

## DDD Sources

- `docs/02-domains/payment.md`

## Language Rationale

Java is a strong default for money state machines, idempotency, and audit-heavy flows.

## Skeleton Check

```bash
mvn test
```
