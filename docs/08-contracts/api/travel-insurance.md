# Travel Insurance — HTTP API

Last updated: 2026-09-25.

## Scope

Travel Insurance owns insurance products, policies, claims and payout decisions. Wallet / Promotion owns the wallet ledger. A claim is `PAID_OUT` only after Travel Insurance has issued and read back a matching Wallet stored-value benefit.

All state-changing endpoints require a UUID-v7 `Idempotency-Key`. Money uses `{currency, minorUnits}`, timestamps are RFC3339 UTC, JSON fields are camelCase and enum values are SCREAMING_SNAKE_CASE.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/v1/policies` | Issue a policy for a paid insurance ancillary item. |
| GET | `/api/v1/policies/{policyId}` | Read the current policy snapshot. |
| POST | `/api/v1/claims` | File a claim, optionally requesting immediate payout. |
| GET | `/api/v1/claims/{claimId}` | Read the current claim snapshot. |
| POST | `/api/v1/claims/{claimId}/approve` | Approve a pending claim and execute its Wallet payout. |
| POST | `/api/v1/claims/{claimId}/reject` | Reject a pending claim. |

## Policy issue

`POST /api/v1/policies` requires `productCode`, `productVersion`, `journeyOrderId`, `ancillaryOrderItemId`, `accountId`, `travelerRef`, nonempty `segmentRefs`, the captured insurance `paymentIntentId`, and a valid coverage window. Cancellation insurance also requires a positive `ticketPrice`.

In persistent runtime, Travel Insurance verifies the order, ancillary item and captured insurance payment through their owning APIs. Equal business replays return the existing policy; conflicting purchase facts are rejected.

## Claim filing

`POST /api/v1/claims` requires `policyId`, `claimType`, `triggerFactKey` and positive `claimedAmount`. Manual claims require evidence references. Automatic delay claims require a supported delay fact over 60 minutes.

Optional `settle=true` executes the same payout workflow as the approval endpoint. `payoutTarget`, when supplied, must be `WALLET_BENEFIT`; claim proceeds are not an original-payment refund.

## Claim approval and payout

`POST /api/v1/claims/{claimId}/approve` accepts an optional body:

```json
{
  "payoutTarget": "WALLET_BENEFIT",
  "reasonCode": "TRAIN_DELAY_GT_60"
}
```

The default target is `WALLET_BENEFIT`. The operation:

1. serializes on the claim and persists one `REQUESTED` `PayoutAdvice`;
2. derives a stable UUID-v7 Wallet idempotency key from the frozen claim decision;
3. calls Wallet / Promotion `POST /api/v1/benefits` with `benefitType=BALANCE`, `balanceType=STORED_VALUE`, `issuanceSource=MANUAL_OPS`, and `issuanceRef=insurance-claim:{claimId}:v1`;
4. reads the benefit from `GET /api/v1/benefits/{benefitId}`;
5. verifies account, amount, currency, reason and `businessReason.referenceType=INSURANCE_CLAIM`, `referenceId=claimId`;
6. records `executionRef=benefitId`, then marks the advice `COMPLETED` and the claim and policy `PAID_OUT` in one transaction with `InsurancePayoutCompleted`.

A timeout, non-2xx response, malformed response or mismatched read-back returns HTTP 503 `UNAVAILABLE`. The claim remains `APPROVED` and its advice remains `REQUESTED`. Retrying reuses the same advice, Wallet idempotency key and issuance identity. HTTP success is not accepted as proof without the read-back verification.

After a successful itinerary change, Travel Insurance consumes `JourneyOrderPostSalesAdjusted`, reads the applied Post Sales case and the current Journey Order through their owning APIs, and moves each affected still-issued policy to the replacement segment and departure/arrival window. The event's segment list must contain the replacement segment; the account and traveler scope must match. Replays are no-ops.

### PayoutAdvice response

| Field | Type | Description |
|---|---|---|
| `payoutAdviceId` | string | Canonical advice ID. |
| `claimId` | string | Approved claim. |
| `policyId` | string | Covered policy. |
| `accountId` | string | Wallet recipient. |
| `payoutTarget` | enum | `WALLET_BENEFIT`. |
| `amount` | Money | Approved payout. |
| `reasonCode` | string | Frozen payout reason. |
| `idempotencyKey` | UUID v7 | Stable Wallet command key. |
| `decisionVersion` | string | Current decision version, `v1`. |
| `executionRef` | string | Verified Wallet benefit ID; present only when completed. |
| `status` | enum | `REQUESTED` or `COMPLETED`. |
| `requestedAt` | timestamp | Decision persistence time. |
| `completedAt` | timestamp | Wallet verification time; present only when completed. |

## Errors

The API uses the common error body and codes. Validation errors return 400, missing resources return 404, conflicting payout decisions return 409, domain violations return 422, and payout or publication dependency failures return 503.
