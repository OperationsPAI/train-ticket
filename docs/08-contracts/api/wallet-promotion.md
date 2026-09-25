# Wallet / Promotion HTTP API

Last updated: 2026-09-25.

`POST_SALES_COMP` issuance requires one benefit per case.
If a benefit exists without its persisted issuance request, a new request returns HTTP 409 `CONFLICT` and preserves the existing balance.

## Overview

Wallet / Promotion owns promotion instruments, account wallet balances and idempotent benefit redemption.
Monetary fields use `Money` with `currency` and integer `minorUnits`.
Complaint compensation reaches the service through an issuance request carrying `issuanceSource=POST_SALES_COMP` and `caseId`.
Post Sales records and executes that request after approving the compensation amount.
The original tickets, seat reservations, order and captured payment are preserved during compensation.
Finance Settlement has benefit cost event handlers, and wallet issuance produces `BenefitIssued` for downstream consumers.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs, timestamps, event envelope fields, pagination conventions and `Money`.
All timestamps are RFC3339 UTC.
JSON fields are camelCase and enum values are SCREAMING_SNAKE_CASE.

## Status enum

The wire status enum is the seven-state `PromotionInstrument` state machine from
the Wallet / Promotion domain document:

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `ISSUED` | Benefit has been issued and is available for use. | `RESERVED`, `REDEEMED`, `EXPIRED`, `REVOKED` |
| `RESERVED` | Benefit value is temporarily frozen for an order, post-sales case, or other business use. | `REDEEMED`, `RELEASED`, `EXPIRED` |
| `REDEEMED` | Benefit has been consumed. | `REVERSED` |
| `RELEASED` | A previous reservation was released and value is available again. | `RESERVED`, `REDEEMED`, `EXPIRED` |
| `EXPIRED` | Validity window elapsed before use. | - |
| `REVOKED` | Issued benefit was administratively or commercially revoked. | - |
| `REVERSED` | A previous redemption was reversed by a compensating business action. | - |

`EXPIRED`, `REVOKED`, and `REVERSED` are terminal.
The expiry scheduler runs every five seconds by default; expiry has no public HTTP endpoint.

## Wallet balance invariants

A `WalletAccount` is the per-account ledger view for benefit balances.

- Each balance mutation MUST carry a non-empty `businessReason`.
- Available and reserved/frozen balances MUST never be negative:
  `availableBalance.minorUnits >= 0` and `reservedBalance.minorUnits >= 0` for
  every `(accountId, balanceType, currency)` sub-ledger.
- Money arithmetic MUST reject cross-currency operations at the domain boundary.
- Every mutation appends a ledger entry and increments the affected aggregate
  version. Consumers should use the event envelope `eventId` for deduplication.

## Common enums

| Enum | Values |
|---|---|
| `benefitType` | `BALANCE`, `COUPON`, `COMPENSATION_CREDIT`, `POINTS` |
| `balanceType` | `STORED_VALUE`, `POINTS`, `PROMOTION_CREDIT` |
| `issuanceSource` | `MANUAL_OPS`, `POST_SALES_COMP`, `DISRUPTION_COMP` |
| `businessReason.reasonType` | `MANUAL_OPS`, `POST_SALES_COMP`, `DISRUPTION_COMP`, `ORDER_PURCHASE`, `RESERVATION_TIMEOUT`, `CUSTOMER_SERVICE_ADJUSTMENT`, `SYSTEM_EXPIRY`, `REVERSAL` |

`businessReason` is the cross-command audit reason object:

| Field | Type | Required | Description |
|---|---|---|---|
| `reasonType` | enum | yes | SCREAMING_SNAKE_CASE reason type from the table above. |
| `reasonCode` | string | yes | Stable business code such as `GOODWILL_COMP` or `ORDER_BENEFIT_USE`. |
| `referenceType` | string | no | Referenced object class, for example `ORDER`, `POST_SALES_CASE`, `MANUAL_ACTION`, or `SCHEDULER_JOB`. |
| `referenceId` | string | no | Referenced aggregate or case ID. Post Sales supplies the canonical `caseId` for `POST_SALES_COMP` issuance. |
| `description` | string | no | Human-readable operational reason. Do not include unmasked documents or other sensitive personal data. |

## Resource representations

### PromotionInstrument

| Field | Type | Required | Description |
|---|---|---|---|
| `benefitId` | string | yes | Canonical benefit ID (`ben-<uuid>`). |
| `accountId` | string | yes | Account that owns the benefit. |
| `walletAccountId` | string | yes | Wallet account ledger ID (`wac-<uuid>`). |
| `benefitType` | enum | yes | One of `BALANCE`, `COUPON`, `COMPENSATION_CREDIT`, `POINTS`. |
| `balanceType` | enum | yes | Ledger bucket affected by this benefit. |
| `status` | enum | yes | One of the seven statuses above. |
| `issuedAmount` | Money | yes | Original issued value. |
| `availableAmount` | Money | yes | Currently available amount; never negative. |
| `reservedAmount` | Money | yes | Currently frozen amount; never negative. |
| `redeemedAmount` | Money | yes | Amount already redeemed. |
| `issuanceSource` | enum | yes | `MANUAL_OPS`, `POST_SALES_COMP`, or `DISRUPTION_COMP`. |
| `caseId` | string | no | Post Sales case reference retained for `POST_SALES_COMP`; other issuance sources omit it. |
| `applicableScope` | object | yes | Scope/rules for eligibility. See `ApplicableScope`. |
| `redemptionRule` | object | yes | Redemption rule such as single-use and maximum amount. See `RedemptionRule`. |
| `revocationRule` | object | yes | Revocation rule for allowed administrative/business revoke conditions. |
| `validFrom` | RFC3339 UTC | yes | Start of validity window. |
| `validUntil` | RFC3339 UTC | yes | End of validity window. |
| `businessReason` | object | yes | Reason for the latest state or balance change. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |
| `version` | integer | yes | Aggregate version after the latest mutation. |

`ApplicableScope`:

| Field | Type | Required | Description |
|---|---|---|---|
| `scopeType` | enum | yes | `ANY_TRIP`, `ORDER`, `POST_SALES_CASE`, `SERVICE_SEGMENT`, or `PRODUCT_CATEGORY`. |
| `referenceIds` | array[string] | no | Optional IDs constraining the scope. |
| `currency` | string | yes | ISO-4217 currency for monetary benefits. |

`referenceIds` is empty for `ANY_TRIP` and required for every narrower scope.
It contains order IDs for `ORDER`, Post Sales case IDs for `POST_SALES_CASE`,
segment IDs for `SERVICE_SEGMENT`, and fare `productCode` values for
`PRODUCT_CATEGORY`.

Every reserve and redeem request is checked against current authoritative
business data. Wallet / Promotion reads the Journey Order named by
`useReference.orderId` and verifies its account, currency and segment set. A
`POST_SALES_CASE` reference is resolved through Post Sales and must point back
to that same order and account. A `PRODUCT_CATEGORY` scope is resolved through
the order's accepted Offer and Fare Quote. Missing, unavailable or contradictory
facts fail closed; request fields alone are not accepted as proof of scope.

`RedemptionRule`:

| Field | Type | Required | Description |
|---|---|---|---|
| `singleUse` | boolean | yes | If true, the first successful redemption moves the benefit to `REDEEMED`. |
| `maxRedemptionAmount` | Money | no | Maximum redeemable value for one redemption. |
| `requiresReservation` | boolean | yes | Whether `ReserveBenefit` must precede redemption. |

### WalletAccount

| Field | Type | Required | Description |
|---|---|---|---|
| `walletAccountId` | string | yes | Wallet account ID (`wac-<uuid>`). |
| `accountId` | string | yes | Owning Account context ID. |
| `balances` | array[WalletBalance] | yes | Per `(balanceType, currency)` sub-ledger balances. |
| `lastLedgerEntryId` | string | no | Last applied ledger entry (`wle-<uuid>`). |
| `updatedAt` | RFC3339 UTC | yes | Last ledger update timestamp. |
| `version` | integer | yes | Wallet account aggregate version. |

`WalletBalance`:

| Field | Type | Required | Description |
|---|---|---|---|
| `balanceType` | enum | yes | `STORED_VALUE`, `POINTS`, or `PROMOTION_CREDIT`. |
| `availableBalance` | Money | yes | Available amount; `minorUnits` MUST be non-negative. |
| `reservedBalance` | Money | yes | Frozen amount; `minorUnits` MUST be non-negative. |
| `redeemedBalance` | Money | yes | Cumulative redeemed amount. |

## Endpoints

### Issue Benefit

**POST** `/api/v1/benefits`

**Idempotency:** REQUIRED (`Idempotency-Key` header as UUID v7).
Replays with the same key and request return the stored response.
Reusing a stored key with a different request returns HTTP 422 `IDEMPOTENCY_KEY_REUSED`.

For `issuanceSource=POST_SALES_COMP`, the service also deduplicates by `caseId` independently of the HTTP idempotency key.
The same case and an equal `IssueBenefitCommand` return the existing benefit with HTTP 201, including when a new HTTP key is supplied.
Equality covers account, amount and currency, benefit and balance types, issuance source, case reference, applicable scope, redemption and revocation rules, validity timestamps and the complete business reason.
A different command for an already recorded case returns HTTP 409 `CONFLICT` with message `compensation case already has another issuance request`.
An accepted case replay adds no benefit, wallet credit, ledger entry or issuance event.

The PostgreSQL implementation locks the case and receiving account with transaction advisory locks and records the original command in `compensation_issuances`.
Its `case_id` primary key and unique `benefit_id` bind one benefit to the case.
The issuance record, benefit, wallet ledger and event outbox are written within the issuance transaction.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Account receiving the benefit. |
| `benefitType` | enum | yes | `BALANCE`, `COUPON`, `COMPENSATION_CREDIT`, or `POINTS`. |
| `balanceType` | enum | yes | Wallet sub-ledger to credit. |
| `amount` | Money | yes | Issued amount. `minorUnits` must be positive. |
| `issuanceSource` | enum | yes | `MANUAL_OPS`, `POST_SALES_COMP`, or `DISRUPTION_COMP`. |
| `caseId` | string | conditional | Required and retained for `POST_SALES_COMP`; other sources do not retain this field in the issued benefit. Recovery references can be carried in `businessReason`. |
| `applicableScope` | object | yes | Eligibility scope. |
| `redemptionRule` | object | yes | Redemption rule. |
| `revocationRule` | object | yes | Revocation rule. |
| `validFrom` | RFC3339 UTC | yes | Start of validity window. |
| `validUntil` | RFC3339 UTC | yes | End of validity window; must be after `validFrom`. |
| `businessReason` | object | yes | Business reason for issuance. |

**Response (201):** `PromotionInstrument` resource.

**Domain effects:** An initial issuance creates or updates the account's `WalletAccount`, credits the matching available balance and emits `BenefitIssued`.

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`, `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`.

### Approved complaint compensation request

The current Post Sales executor supplies these fields to `POST /api/v1/benefits`:

| Field | Value |
|---|---|
| `accountId` | The original order account. |
| `amount` | The approved `decision.compensationAmount`, matching currency and minor units. |
| `benefitType` | `COMPENSATION_CREDIT` |
| `balanceType` | `PROMOTION_CREDIT` |
| `issuanceSource` | `POST_SALES_COMP` |
| `caseId` | Canonical `psc-` case ID. |
| `applicableScope` | `scopeType=ANY_TRIP` and the approved currency. |
| `redemptionRule` | `singleUse=false`, `requiresReservation=false`. |
| `revocationRule` | An empty request object. |
| `validFrom` | Time the executor creates the persisted request. |
| `validUntil` | `validFrom` plus 30 days. |
| `businessReason` | `reasonType=POST_SALES_COMP`, the case `reasonCode`, `referenceType=POST_SALES_CASE`, `referenceId=caseId`. |

The 30 day window is supplied by the current compensation executor and retained in its persisted request across retries.
The wallet API uses the explicit validity fields supplied by its caller.
Post Sales uses a stable wallet idempotency key derived from the canonical case ID and `wallet-compensation` operation name.
It records the request in `compensationOperations.request`, the checked wallet response in `issued` and the final verification in `verified`.
The executor requires an approved plan containing `ISSUE_COMPENSATION_CREDIT` and checks the original payment is `CAPTURED` with zero `refundedAmount`.
The verification record includes the original `paymentIntentId` and `capturedAmount`.
It rereads positive issuance through `GET /api/v1/benefits/{benefitId}` before completing the case as `APPLIED`.
Completion records `ISSUE_COMPENSATION_CREDIT` and `APPLY_RESULT` as `SUCCEEDED` in order.
Public Post Sales status remains `APPROVED` during these advances.
Zero approved amounts complete in Post Sales without calling this issuance endpoint.
See [Post Sales execution](post-sales.md) for the advance endpoint, evidence and completion rules.

### Get Benefit

**GET** `/api/v1/benefits/{benefitId}`

**Response (200):** `PromotionInstrument` resource.

**Error codes:** `NOT_FOUND`

### List Benefits by Account

**GET** `/api/v1/benefits?byAccountId={accountId}&limit=20&offset=0`

`byAccountId` is required and contains the owning `accountId`.
The endpoint requires an account filter.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `byAccountId` | string | yes | Owning account ID used to filter benefits. |
| `status` | enum | no | Optional seven-state status filter. |
| `benefitType` | enum | no | Optional benefit type filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `PromotionInstrument` resource.

**Error codes:** `VALIDATION_FAILED`

### Reserve Benefit

**POST** `/api/v1/benefits/{benefitId}/reserve`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `amount` | Money | yes | Amount to freeze. Must be positive, same currency as the benefit, and not exceed available amount. |
| `reservationRef` | string | yes | External reservation reference, for example an order or post-sales workflow reference. |
| `reservationExpiresAt` | RFC3339 UTC | yes | Reservation expiry timestamp. |
| `useReference` | object | yes | Authoritative order/business reference used for scope validation. See below. |
| `businessReason` | object | yes | Business reason for freezing value. |

`useReference` contains required `orderId`, optional `postSalesCaseId`, and
optional `segmentRefs`. `segmentRefs` must belong to the order. The business
reason must identify the same order, Post Sales case, or one of the same
segments. For compatibility, an older request without `useReference` is
accepted only when `businessReason.referenceType=ORDER` and its `referenceId`
exactly equals `reservationRef`; that order is still fetched and verified.

**Response (200):** `PromotionInstrument` resource with status `RESERVED`.

**Domain effects:** moves amount from available to reserved balance and emits
`BenefitReserved`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Redeem Benefit

**POST** `/api/v1/benefits/{benefitId}/redeem`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `amount` | Money | yes | Amount to redeem. Must be positive, same currency as the benefit, and satisfy the redemption rule. |
| `redemptionRef` | string | yes | Business reference for this redemption, for example `ord-<uuid>` or a post-sales workflow reference. |
| `reservationRef` | string | no | Reservation being consumed when redemption follows `ReserveBenefit`. |
| `useReference` | object | yes | Authoritative order/business reference used for scope validation. |
| `businessReason` | object | yes | Business reason. The same benefit and the same reason cannot be redeemed twice. |

The same `useReference` checks as reservation apply. For compatibility, an
older request without it is accepted only when an `ORDER` business reason
exactly matches `redemptionRef`; the referenced order is still fetched and
verified.

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `benefit` | PromotionInstrument | Updated benefit resource, normally status `REDEEMED` when the redemption consumes the redeemable amount or the rule is `singleUse=true`. |
| `redemption` | BenefitRedemption | Idempotent redemption record. |

`BenefitRedemption` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `redemptionId` | string | yes | Redemption ID (`brd-<uuid>`). |
| `benefitId` | string | yes | Redeemed benefit. |
| `accountId` | string | yes | Owning account. |
| `amount` | Money | yes | Redeemed amount. |
| `redemptionRef` | string | yes | Business reference supplied in the request. |
| `businessReason` | object | yes | Idempotency/audit reason. |
| `redeemedAt` | RFC3339 UTC | yes | Redemption timestamp. |

**Domain effects:** moves amount from reserved or available balance to redeemed
balance and emits `BenefitRedeemed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Release Reserved Benefit

**POST** `/api/v1/benefits/{benefitId}/release`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `amount` | Money | yes | Reserved amount to release. Must be positive, same currency, and not exceed reserved amount. |
| `reservationRef` | string | yes | Reservation being released. |
| `businessReason` | object | yes | Business reason, for example timeout or order cancellation. |

**Response (200):** `PromotionInstrument` resource with status `RELEASED`.

**Domain effects:** moves amount from reserved back to available balance and
emits `BenefitReservationReleased`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Revoke Benefit

**POST** `/api/v1/benefits/{benefitId}/revoke`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `businessReason` | object | yes | Business reason for revocation. |
| `effectiveAt` | RFC3339 UTC | no | Revocation effective timestamp; defaults to request processing time. |

**Response (200):** `PromotionInstrument` resource with status `REVOKED`.

**Domain effects:** removes remaining available value from the wallet ledger,
requires reserved amount to be zero, and emits `BenefitRevoked`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Reverse Redemption

**POST** `/api/v1/benefits/{benefitId}/reverse-redemption`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `redemptionId` | string | yes | Redemption to reverse. |
| `amount` | Money | yes | Amount to reverse. Must be positive, same currency, and not exceed the redemption's unreversed amount. |
| `businessReason` | object | yes | Business reason for reversal. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `benefit` | PromotionInstrument | Updated benefit resource with status `REVERSED`. |
| `reversedRedemptionId` | string | Redemption that was reversed. |
| `reversedAt` | RFC3339 UTC | Reversal timestamp. |

**Domain effects:** compensates a prior redemption, restores value according to
policy, and emits `BenefitRedemptionReversed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Wallet Account

**GET** `/api/v1/wallet-accounts/{accountId}`

The path parameter is the Account context `accountId`. The response returns the
Wallet / Promotion ledger view for that account.

**Response (200):** `WalletAccount` resource.

**Error codes:** `NOT_FOUND`

## Bus-only behavior

`ExpireBenefit` is triggered by the expiry scheduler when `validUntil` has elapsed.
It moves eligible active benefits to `EXPIRED`, clears their remaining available and reserved balances, carries `businessReason.reasonType=SYSTEM_EXPIRY`, and emits `BenefitExpired`.
