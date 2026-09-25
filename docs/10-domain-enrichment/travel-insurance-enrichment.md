# Travel Insurance implementation

## Implementation

Travel Insurance is a Go service with versioned products, policies, claims and payout advice.
The product catalog contains delay, cancellation, accident and baggage insurance, with fixed premiums or a ticket price percentage.
Policy issuance verifies purchase references in the configured persistent runtime and records the selected product version and coverage window.
Claims can be filed, approved or rejected; `TrainDelayed` processing creates automatic delay claims when the delay exceeds 60 minutes and the segment and coverage match.
Approval records a durable `REQUESTED` payout advice and executes it through Wallet / Promotion's owning benefit API. The claim and policy move to `PAID_OUT`, and `InsurancePayoutCompleted` is emitted, only after the issued Wallet benefit has been read back and verified against the approved account, currency, amount and claim reference.
Policy cancellation checks the recorded premium refund before publishing cancellation.

## Interfaces and sources

[Service README](../../services/travel-insurance/README.md) and [HTTP router](../../services/travel-insurance/api/router.go) document `/api/v1/policies`, `/api/v1/claims` and claim approval or rejection.
[Products](../../services/travel-insurance/domain/product.go), [claims](../../services/travel-insurance/domain/claim.go), [application service](../../services/travel-insurance/application/service.go), [purchase verification](../../services/travel-insurance/application/purchase_verification.go) and [event subscriber](../../services/travel-insurance/adapters/messaging/subscriber.go) define current behavior.

## Boundaries

`PayoutAdvice` records both the insurance decision and the verified Wallet execution reference. A failed or uncertain call leaves the advice retryable and does not claim money was delivered. The stable downstream idempotency key and `insurance-claim:{claimId}:v1` issuance identity prevent duplicate credits after a timeout or process restart.
Migration `004_payout_execution.sql` reopens legacy local-only `PAID_OUT` records as `APPROVED` / `REQUESTED`; they must obtain and verify a Wallet execution reference before becoming paid again.
The cancellation path verifies a premium refund and does not initiate that refund.
Deployment and verification evidence, including differences from workspace code, are recorded in [current status](../00-current-status.md).
Remaining work is maintained only in [TODO](../TODO.md).
