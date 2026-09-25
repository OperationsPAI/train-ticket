# Travel Insurance Events

Last updated: 2026-09-25.

Travel Insurance publishes to `events:travel-insurance`. Event envelopes follow the shared messaging contract. Money uses `{currency, minorUnits}`.

## InsurancePolicyIssued

Published after a paid ancillary insurance purchase has been verified and the policy snapshot is committed. The payload includes `policyId`, `policyNumber`, product code and version, order, ancillary item, account, traveler, segment references, premium, coverage limit, coverage window and `status`.

## InsuranceClaimCreated

Published after a claim is persisted. The payload includes `claimId`, `policyId`, claim type, trigger fact, claimed and approved amount, evidence references and current status.

## InsuranceClaimApproved

Published when a pending manual claim first becomes approved. Automatic delay claims are already approved when created and do not emit a second approval transition during payout retry.

## InsuranceClaimRejected

Published when a pending claim is rejected with its stable reason code.

## InsurancePayoutCompleted

This is a delivered-value fact, not a local decision. It is published only after Travel Insurance has read back a matching Wallet benefit and committed the completed advice, claim and policy.

Required payload fields:

| Field | Type | Description |
|---|---|---|
| `claimId` | string | Paid claim. |
| `policyId` | string | Covered policy. |
| `payoutAdviceId` | string | Durable payout decision. |
| `payoutTarget` | enum | `WALLET_BENEFIT`. |
| `amount` | Money | Wallet value delivered. |
| `reasonCode` | string | Approved reason. |
| `idempotencyKey` | UUID v7 | Stable Wallet command key. |
| `executionRef` | string | Verified Wallet benefit ID. |
| `status` | enum | `COMPLETED`. |
| `completedAt` | timestamp | Verification and local completion time. |

Consumers must deduplicate by envelope `eventId`. They may treat `executionRef` as evidence that the Wallet owner accepted and exposes the matching benefit. Absence of this event, a `REQUESTED` advice, or a claim that remains `APPROVED` must not be accounted as paid.

## InsurancePolicyCancelled

Published after a settled premium refund has been verified and the policy is cancelled. This event represents premium return, which is separate from claim payout.
