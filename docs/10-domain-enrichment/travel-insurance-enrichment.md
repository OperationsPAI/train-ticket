# Travel Insurance implementation

## Implementation

Travel Insurance is a Go service with versioned products, policies, claims and payout advice.
The product catalog contains delay, cancellation, accident and baggage insurance, with fixed premiums or a ticket price percentage.
Policy issuance verifies purchase references in the configured persistent runtime and records the selected product version and coverage window.
Claims can be filed, approved or rejected; `TrainDelayed` processing creates automatic delay claims when the delay exceeds 60 minutes and the segment and coverage match.
Approval records a payout advice, marks the local claim and policy paid out and emits `InsurancePayoutCompleted`.
Policy cancellation checks the recorded premium refund before publishing cancellation.

## Interfaces and sources

[Service README](../../services/travel-insurance/README.md) and [HTTP router](../../services/travel-insurance/api/router.go) document `/api/v1/policies`, `/api/v1/claims` and claim approval or rejection.
[Products](../../services/travel-insurance/domain/product.go), [claims](../../services/travel-insurance/domain/claim.go), [application service](../../services/travel-insurance/application/service.go), [purchase verification](../../services/travel-insurance/application/purchase_verification.go) and [event subscriber](../../services/travel-insurance/adapters/messaging/subscriber.go) define current behavior.

## Boundaries

`PayoutAdvice`, local `PAID_OUT` status and `InsurancePayoutCompleted` record the insurance decision.
The approval method contains no Payment refund or Wallet credit call, so those records alone do not establish delivery of funds.
The cancellation path verifies a premium refund and does not initiate that refund.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md).
Remaining work is maintained only in [TODO](../TODO.md).
