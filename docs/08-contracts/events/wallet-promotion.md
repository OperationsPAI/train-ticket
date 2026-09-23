# Wallet / Promotion Events & Commands

Last updated: 2026-09-24.

Runtime baseline: Helm revision 279.
Remaining work is tracked in [TODO](../../TODO.md).

## Scope

This document covers `PromotionInstrument` lifecycle events, account wallet ledger effects and idempotent `BenefitRedemption` records.
Complaint compensation is driven by an HTTP issuance request carrying `issuanceSource=POST_SALES_COMP` and the canonical Post Sales `caseId`.
The compensation executor calls wallet issuance after approval and records the returned benefit as execution evidence.
The issuance credits the order account with the approved amount while preserving the original tickets, seat reservations, order and captured payment.
Finance Settlement records compensation issuance cost from `BenefitIssued` only and skips refund result processing for `PostSalesApplied` with `caseType=COMPENSATION`.
Its cost query also contains later benefit lifecycle deltas, without another issuance charge from Post Sales approval or completion.
Every event payload carries the `businessReason` that caused the state or balance change.
Reason descriptions must not contain unmasked documents or other sensitive personal data.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE and all timestamps are RFC3339 UTC.
Envelope fields, including optional trace context propagation, follow `docs/08-contracts/messaging.md` and `docs/08-contracts/shared-primitives.md`.
Monetary values use `Money` as `{currency, minorUnits}`.

## Event identity and idempotency

Wallet / Promotion producers MUST assign deterministic event IDs per aggregate
transition so that retries do not create duplicate facts. The event ID seed is:

```
wallet-promotion:<eventType>:<benefitId>:<aggregateVersion>
```

where `aggregateVersion` is the `PromotionInstrument` version after applying the
transition. If a command replay does not apply a new transition, no new event is
emitted. Consumers still deduplicate by envelope `eventId`.

For `POST_SALES_COMP` issuance, the service compares the complete `IssueBenefitCommand` with the command stored for `caseId`.
An identical command returns the existing benefit even if its HTTP idempotency key differs, with no additional wallet credit, ledger entry or `BenefitIssued` event.
A different command for the same case returns HTTP 409 `CONFLICT` and creates no issuance event.
Equality includes the recipient, monetary value, types, scope, rules, validity timestamps and complete business reason.
The PostgreSQL implementation serializes issuance with transaction advisory locks for the case and account.
It records the case, unique benefit ID and request in `compensation_issuances` in the same transaction as the benefit, wallet ledger and event outbox.
HTTP response replay remains separate: a stored idempotency key reused with a different request returns `IDEMPOTENCY_KEY_REUSED`.

`BenefitRedeemed` additionally represents the idempotent `BenefitRedemption`
record. The same `(benefitId, businessReason.reasonType,
businessReason.reasonCode, businessReason.referenceType,
businessReason.referenceId)` MUST NOT be redeemed twice.

## Status enum

Event payloads use the same seven-state enum as the HTTP API: `ISSUED`,
`RESERVED`, `REDEEMED`, `RELEASED`, `EXPIRED`, `REVOKED`, `REVERSED`.

## Common payload objects

`businessReason` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `reasonType` | enum | yes | `MANUAL_OPS`, `POST_SALES_COMP`, `DISRUPTION_COMP`, `ORDER_PURCHASE`, `RESERVATION_TIMEOUT`, `CUSTOMER_SERVICE_ADJUSTMENT`, `SYSTEM_EXPIRY`, or `REVERSAL`. |
| `reasonCode` | string | yes | Stable business reason code. |
| `referenceType` | string | no | Referenced object class such as `ORDER`, `POST_SALES_CASE`, `MANUAL_ACTION`, or `SCHEDULER_JOB`. |
| `referenceId` | string | no | Referenced object ID. For post-sales or disruption compensation this is the carried `caseId`. |
| `description` | string | no | Human-readable reason; must not contain unmasked sensitive personal data. |

`walletBalanceDelta` fields used by all balance-changing events:

| Field | Type | Required | Description |
|---|---|---|---|
| `walletAccountId` | string | yes | Wallet account ID (`wac-<uuid>`). |
| `balanceType` | enum | yes | `STORED_VALUE`, `POINTS`, or `PROMOTION_CREDIT`. |
| `availableDelta` | Money | yes | Change to available balance. May be negative, but resulting balance must not be negative. |
| `reservedDelta` | Money | yes | Change to reserved/frozen balance. May be negative, but resulting balance must not be negative. |
| `redeemedDelta` | Money | yes | Change to cumulative redeemed balance. |
| `availableBalanceAfter` | Money | yes | Available balance after the transition; `minorUnits` must be non-negative. |
| `reservedBalanceAfter` | Money | yes | Reserved/frozen balance after the transition; `minorUnits` must be non-negative. |
| `ledgerEntryId` | string | yes | Wallet ledger entry ID (`wle-<uuid>`). |

## Published Events

### BenefitIssued

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | finance-settlement, notification, reporting |
| **Trigger** | `IssueBenefit` command accepted from manual operations or post-sales compensation issuance request. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Issued benefit ID (`ben-<uuid>`). |
| `accountId` | string | yes | Account receiving the benefit. |
| `walletAccountId` | string | yes | Wallet account credited by issuance. |
| `benefitType` | enum | yes | `BALANCE`, `COUPON`, `COMPENSATION_CREDIT`, or `POINTS`. |
| `balanceType` | enum | yes | Credited wallet sub-ledger. |
| `issuedAmount` | Money | yes | Original issued value. |
| `availableAmount` | Money | yes | Available amount after issuance. |
| `reservedAmount` | Money | yes | Reserved amount after issuance; normally zero. |
| `issuanceSource` | enum | yes | `MANUAL_OPS`, `POST_SALES_COMP`, or `DISRUPTION_COMP`. |
| `caseId` | string | no | Post Sales case reference retained for `POST_SALES_COMP`; other issuance sources omit it and can retain recovery context in `businessReason`. |
| `applicableScope` | object | yes | Benefit eligibility scope. |
| `redemptionRule` | object | yes | Redemption rule captured at issuance. |
| `revocationRule` | object | yes | Revocation rule captured at issuance. |
| `validFrom` | RFC3339 UTC | yes | Start of validity window. |
| `validUntil` | RFC3339 UTC | yes | End of validity window. |
| `businessReason` | object | yes | Business reason for issuance. |
| `walletBalanceDelta` | object | yes | Ledger delta; `availableDelta` equals `issuedAmount`. |
| `issuedAt` | RFC3339 UTC | yes | Issuance timestamp. |
| `status` | enum | yes | `ISSUED`. |
| `aggregateVersion` | integer | yes | Benefit version after issuance. |

For an approved complaint compensation, `benefitType=COMPENSATION_CREDIT`, `balanceType=PROMOTION_CREDIT` and `issuanceSource=POST_SALES_COMP`.
`issuedAmount` and `walletBalanceDelta.availableDelta` equal the approved Post Sales `compensationAmount` in currency and minor units.
`accountId` identifies the original order account, and `caseId` carries the canonical `psc-` case reference.
`businessReason` uses `reasonType=POST_SALES_COMP`, the case `reasonCode`, `referenceType=POST_SALES_CASE` and `referenceId=caseId`.
The current Post Sales request supplies `ANY_TRIP` scope, `singleUse=false`, `requiresReservation=false` and an empty `revocationRule` request object.
The event carries the accepted rules and original validity timestamps: `validFrom` is the recorded request creation time and `validUntil` is 30 days later.
Retries retain this window.
Zero approved amounts complete in Post Sales without a wallet issuance or `BenefitIssued` event.

Post Sales stores the checked wallet response in `compensationOperations.issued` and rereads the benefit before completing the case.
Its `verified` record includes preserved ticket and payment flags, original `paymentIntentId` and `capturedAmount`, approved `amount` and `benefitId`.
`execute-compensation` exposes public status `APPROVED` during execution and `APPLIED` after verification and successful completion of both plan steps.
See [Post Sales events](post-sales.md) for the approval actions, execution plan and completion payload.

### BenefitReserved

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | reporting; finance-settlement and notification ack-skip. |
| **Trigger** | `ReserveBenefit` command freezes available benefit value for an order, post-sales workflow, or other business reference. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Reserved benefit ID. |
| `accountId` | string | yes | Owning account. |
| `walletAccountId` | string | yes | Wallet account whose balance was frozen. |
| `benefitType` | enum | yes | Benefit type. |
| `balanceType` | enum | yes | Affected wallet sub-ledger. |
| `reservedAmount` | Money | yes | Amount reserved by this command. |
| `reservationRef` | string | yes | Business reservation reference. |
| `reservationExpiresAt` | RFC3339 UTC | yes | Reservation expiry timestamp. |
| `availableAmount` | Money | yes | Benefit available amount after reservation. |
| `totalReservedAmount` | Money | yes | Benefit reserved amount after reservation. |
| `businessReason` | object | yes | Business reason for the freeze. |
| `walletBalanceDelta` | object | yes | Ledger delta; available decreases and reserved increases by `reservedAmount`. |
| `reservedAt` | RFC3339 UTC | yes | Reservation timestamp. |
| `status` | enum | yes | `RESERVED`. |
| `aggregateVersion` | integer | yes | Benefit version after reservation. |

### BenefitRedeemed

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | finance-settlement, reporting; notification intentionally ack-skips (redemption notifications are noisy). |
| **Trigger** | `RedeemBenefit` command consumes benefit value and creates an idempotent `BenefitRedemption` record. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Redeemed benefit ID. |
| `redemptionId` | string | yes | Idempotent redemption record ID (`brd-<uuid>`). |
| `accountId` | string | yes | Owning account. |
| `walletAccountId` | string | yes | Wallet account debited by redemption. |
| `benefitType` | enum | yes | Benefit type. |
| `balanceType` | enum | yes | Affected wallet sub-ledger. |
| `redeemedAmount` | Money | yes | Amount redeemed by this command. |
| `redemptionRef` | string | yes | Business reference for redemption. |
| `reservationRef` | string | no | Consumed reservation reference when redemption was reserved first. |
| `availableAmount` | Money | yes | Benefit available amount after redemption. |
| `reservedAmount` | Money | yes | Benefit reserved amount after redemption. |
| `totalRedeemedAmount` | Money | yes | Benefit cumulative redeemed amount after redemption. |
| `businessReason` | object | yes | Idempotency/audit reason; same benefit and reason cannot redeem twice. |
| `walletBalanceDelta` | object | yes | Ledger delta from available or reserved into redeemed. |
| `redeemedAt` | RFC3339 UTC | yes | Redemption timestamp. |
| `status` | enum | yes | `REDEEMED` when fully/single-use redeemed; otherwise current lifecycle status after partial redemption. |
| `aggregateVersion` | integer | yes | Benefit version after redemption. |

### BenefitReservationReleased

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | reporting; finance-settlement and notification ack-skip. |
| **Trigger** | `ReleaseReservedBenefit` command releases a prior reservation because the business use ended or timed out. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Benefit whose reservation was released. |
| `accountId` | string | yes | Owning account. |
| `walletAccountId` | string | yes | Wallet account whose frozen value was released. |
| `benefitType` | enum | yes | Benefit type. |
| `balanceType` | enum | yes | Affected wallet sub-ledger. |
| `releasedAmount` | Money | yes | Amount released from reserved value. |
| `reservationRef` | string | yes | Reservation reference being released. |
| `availableAmount` | Money | yes | Benefit available amount after release. |
| `reservedAmount` | Money | yes | Benefit reserved amount after release. |
| `businessReason` | object | yes | Business reason for release. |
| `walletBalanceDelta` | object | yes | Ledger delta; reserved decreases and available increases by `releasedAmount`. |
| `releasedAt` | RFC3339 UTC | yes | Release timestamp. |
| `status` | enum | yes | `RELEASED`. |
| `aggregateVersion` | integer | yes | Benefit version after release. |

### BenefitExpired

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | finance-settlement, notification, reporting |
| **Trigger** | `ExpireBenefit` scheduler/domain policy runs when `validUntil` has elapsed before the benefit reaches a terminal state. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Expired benefit ID. |
| `accountId` | string | yes | Owning account. |
| `walletAccountId` | string | yes | Wallet account adjusted by expiry. |
| `benefitType` | enum | yes | Benefit type. |
| `balanceType` | enum | yes | Affected wallet sub-ledger. |
| `expiredAmount` | Money | yes | Remaining unredeemed amount removed by expiry. |
| `validUntil` | RFC3339 UTC | yes | Validity timestamp that elapsed. |
| `availableAmount` | Money | yes | Benefit available amount after expiry; zero for terminal expiry. |
| `reservedAmount` | Money | yes | Benefit reserved amount after expiry; zero for terminal expiry. |
| `businessReason` | object | yes | Expiry reason, normally `reasonType=SYSTEM_EXPIRY`. |
| `walletBalanceDelta` | object | yes | Ledger delta removing remaining available/reserved exposure without negative balances. |
| `expiredAt` | RFC3339 UTC | yes | Expiry processing timestamp. |
| `status` | enum | yes | `EXPIRED`. |
| `aggregateVersion` | integer | yes | Benefit version after expiry. |

### BenefitRevoked

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | finance-settlement, notification, reporting |
| **Trigger** | `RevokeBenefit` command revokes an issued or released benefit according to its revocation rule. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Revoked benefit ID. |
| `accountId` | string | yes | Owning account. |
| `walletAccountId` | string | yes | Wallet account adjusted by revocation. |
| `benefitType` | enum | yes | Benefit type. |
| `balanceType` | enum | yes | Affected wallet sub-ledger. |
| `revokedAmount` | Money | yes | Remaining available amount removed by revocation. |
| `availableAmount` | Money | yes | Benefit available amount after revocation; zero for terminal revocation. |
| `reservedAmount` | Money | yes | Benefit reserved amount after revocation; must be zero before revoke succeeds. |
| `businessReason` | object | yes | Business reason for revocation. |
| `walletBalanceDelta` | object | yes | Ledger delta removing remaining available value. |
| `revokedAt` | RFC3339 UTC | yes | Revocation timestamp. |
| `status` | enum | yes | `REVOKED`. |
| `aggregateVersion` | integer | yes | Benefit version after revocation. |

### BenefitRedemptionReversed

| Field | Description |
|---|---|
| **Producer** | wallet-promotion |
| **Consumers** | finance-settlement, reporting; notification intentionally ack-skips (redemption reversal notifications are noisy). |
| **Trigger** | `ReverseRedemption` command compensates a prior `BenefitRedeemed` fact. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Benefit whose redemption was reversed. |
| `redemptionId` | string | yes | Original redemption record being reversed. |
| `reversalId` | string | yes | Reversal record ID (`brr-<uuid>`). |
| `accountId` | string | yes | Owning account. |
| `walletAccountId` | string | yes | Wallet account adjusted by reversal. |
| `benefitType` | enum | yes | Benefit type. |
| `balanceType` | enum | yes | Affected wallet sub-ledger. |
| `reversedAmount` | Money | yes | Amount reversed. |
| `availableAmount` | Money | yes | Benefit available amount after reversal according to policy. |
| `reservedAmount` | Money | yes | Benefit reserved amount after reversal. |
| `totalRedeemedAmount` | Money | yes | Benefit cumulative redeemed amount after reversal. |
| `businessReason` | object | yes | Business reason for reversal. |
| `walletBalanceDelta` | object | yes | Ledger delta compensating the redeemed value. |
| `reversedAt` | RFC3339 UTC | yes | Reversal timestamp. |
| `status` | enum | yes | `REVERSED`. |
| `aggregateVersion` | integer | yes | Benefit version after reversal. |

## Accepted Commands

The commands below are implemented by the wallet application service and expiry scheduler.
The [domain design](../../02-domains/wallet-promotion.md) describes their current boundaries.

| Command | Sender / Trigger | Produced event |
|---|---|---|
| `IssueBenefit` | API gateway / operations via `POST /api/v1/benefits`, or post-sales compensation issuance request carrying `caseId` | `BenefitIssued` |
| `ReserveBenefit` | API gateway or order/post-sales workflow via `POST /api/v1/benefits/{benefitId}/reserve` | `BenefitReserved` |
| `RedeemBenefit` | API gateway or business workflow via `POST /api/v1/benefits/{benefitId}/redeem` | `BenefitRedeemed` |
| `ReleaseReservedBenefit` | API gateway or timeout/cancellation workflow via `POST /api/v1/benefits/{benefitId}/release` | `BenefitReservationReleased` |
| `ExpireBenefit` | Wallet / Promotion scheduler/domain policy at `validUntil` | `BenefitExpired` |
| `RevokeBenefit` | API gateway / operations via `POST /api/v1/benefits/{benefitId}/revoke` | `BenefitRevoked` |
| `ReverseRedemption` | API gateway / compensating workflow via `POST /api/v1/benefits/{benefitId}/reverse-redemption` | `BenefitRedemptionReversed` |

## Consumed upstream events

Complaint compensation issuance is driven by `POST /api/v1/benefits` with `caseId`.
The Post Sales executor uses its recorded request and stable downstream idempotency key to obtain and verify the issued benefit.
