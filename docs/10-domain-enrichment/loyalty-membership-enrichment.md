# Loyalty and Membership implementation

## Implementation

Loyalty and Membership is a TypeScript service with member enrollment, points ledgers, expiring points batches and annual tier records.
Purchase `PaymentCaptured` events earn points using supplied fare and enrichment fields; earning rules include seat class, tier and travel bonuses.
Journey Order events record trip counts and restore ticket redemption points on cancellation, while applicable post sales refund events reduce qualifying points.
Ticket redemption applies a minimum of 500 points and a ceiling of half the supplied fare, at 100 points per currency unit.
The domain supports immediate tier upgrades, annual evaluation after a three month grace period and points expiry.
Payment reconciliation verifies the stored purchase payment, order owner and original capture event before applying the original earning fact.

## Interfaces and sources

[HTTP routes](../../services/loyalty-membership/src/app.ts) expose `/members/enroll`, member lookup, redemption, `redeem-ticket`, `expire-points` and `evaluate-tier`.
[Reconciliation API](../08-contracts/api/loyalty-reconciliation.md) documents `POST /internal/payments/{paymentIntentId}/reconcile`.
[Domain](../../services/loyalty-membership/src/domain.ts), [application service](../../services/loyalty-membership/src/application.ts) and [runtime and event handlers](../../services/loyalty-membership/src/bootstrap.ts) define earning, persistence and event processing.

## Boundaries

Bonus calculation depends on fields supplied by the payment event; ordinary capture events do not establish every travel or birthday bonus input.
Ticket redemption records points and a discount amount within Loyalty; it does not itself reduce a Payment intent.
Expiry and annual evaluation are exposed as commands, without a monthly expiry scheduler in the service runtime.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md) for Helm revision 279.
Remaining work is maintained only in [TODO](../TODO.md).
