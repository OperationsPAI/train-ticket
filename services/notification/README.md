# notification

Domain: Notification

Language: typescript

Phase: phase-1-domain-foundation

Status: implemented-foundation for `REQ-019-Notification-domain-foundation`

## Owns in this slice

- `NotificationTask` aggregate root: lifecycle from `Planned` -> `Delivering` -> `Delivered` / `Failed` / `Cancelled`.
- `Template` aggregate root: versioned template with variable schema, channel adaptations (EMAIL, SMS, PUSH, IN_APP), and status lifecycle (Draft, Validated, Published, Retired).
- `RecipientPolicy` aggregate root: per-intent channel priority, fallback order, consent bypass flags, max retries, and throttle configuration.
- `DeliveryReceipt` appended fact: immutable record of a delivery outcome (Delivered, Bounced, Rejected, Timeout, Expired).
- Domain events: `NotificationScheduled`, `NotificationDispatched`, `NotificationDelivered`, `NotificationFailed`, `NotificationCancelled`.
- Send idempotency: same trigger event + recipient + template yields at most one task.

## Key invariants

- Notification failure never rolls back business state.
- Transaction-required notifications (payment result, ticket issued, refund settled) bypass ordinary user notification preferences.
- Same trigger event + recipient + template yields at most one task (send idempotency); retries reference the same task.
- Delivery receipts are appended facts; a receipt never mutates the task's request payload.

## Explicitly does not own

Notification does **not** mutate JourneyOrder, CapacityHold, PaymentIntent, or Entitlement state. Notification failure does not roll back business state.

## DDD Sources

- `docs/02-domains/notification.md`
- `docs/03-ddd-final/phase-1-contract.md`
- `docs/08-contracts/events/notification.md`

## Validation

```bash
npm test
```

The test command compiles TypeScript into ignored `test-output/` files and runs Node's built-in `node:test` suite, including domain unit tests and Fastify inject smoke tests for `/health`, `/livez`, and `/readyz`.
