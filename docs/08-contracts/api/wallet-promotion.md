# Wallet / Promotion — HTTP API

Last updated: 2026-07-08

## Overview

Wallet / Promotion owns non-cash benefits: promotion instruments, the per-account
wallet balance ledger, and idempotent benefit redemption. This contract is scoped
to the ADR-0002 second activation wave and is bounded by
`docs/02-domains/wallet-promotion.md`.

Activation-wave rulings:

- This wave delivers the full `PromotionInstrument` lifecycle and the per-account
  `WalletAccount` balance ledger. Points and stored value are represented as
  balance sub-ledgers with monetary `Money` values (`currency` + integer
  `minorUnits`); there is no decimal or floating-point money on the wire.
- `BenefitRedemption` is idempotent. The same benefit and the same
  `businessReason` MUST NOT produce duplicate redemption effects. A replay of an
  equivalent redemption returns the original redemption result; a different
  amount or reference for the same idempotency key returns
  `IDEMPOTENCY_KEY_REUSED`, and a conflicting same-reason redemption returns
  `CONFLICT`.
- Combination payment is not delivered in this wave. Payment contracts are not
  changed. Future note: a later wave will define the "cash remaining payable"
  interface between Wallet / Promotion and Payment.
- Finance Settlement and Notification are event consumers at the contract
  surface, but their concrete consumption implementations are deferred to a
  later wave.
- Issuance sources supported in this wave are `MANUAL_OPS`,
  `POST_SALES_COMP`, and the additive Disruption Recovery value
  `DISRUPTION_COMP`. Post-sales compensation is carried by the issuance request
  as `caseId`; Disruption Recovery compensation is carried as a recovery
  `caseId`/business reason. Wallet / Promotion does not subscribe to Post Sales
  or Disruption Recovery streams in this wave.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, pagination conventions, and `Money`. All
timestamps are RFC3339 UTC. JSON fields are camelCase and enum values are
SCREAMING_SNAKE_CASE.

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

`EXPIRED`, `REVOKED`, and `REVERSED` are terminal. `ExpireBenefit` is scheduler
or domain-policy driven and has no public HTTP endpoint in this wave.

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
| `referenceId` | string | no | Referenced aggregate or case ID. Use `caseId` for `POST_SALES_COMP` issuance without changing the Post Sales contract. |
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
| `caseId` | string | no | Post-sales case reference when `issuanceSource` is `POST_SALES_COMP`, or Disruption Recovery case reference when `issuanceSource` is `DISRUPTION_COMP`. |
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

**Idempotency:** REQUIRED (`Idempotency-Key` header). Replays with the same body
return the original response. Reusing the same key with a different body returns
`IDEMPOTENCY_KEY_REUSED`.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Account receiving the benefit. |
| `benefitType` | enum | yes | `BALANCE`, `COUPON`, `COMPENSATION_CREDIT`, or `POINTS`. |
| `balanceType` | enum | yes | Wallet sub-ledger to credit. |
| `amount` | Money | yes | Issued amount. `minorUnits` must be positive. |
| `issuanceSource` | enum | yes | `MANUAL_OPS`, `POST_SALES_COMP`, or `DISRUPTION_COMP`. |
| `caseId` | string | no | Required when `issuanceSource` is `POST_SALES_COMP` or `DISRUPTION_COMP`; ignored otherwise. |
| `applicableScope` | object | yes | Eligibility scope. |
| `redemptionRule` | object | yes | Redemption rule. |
| `revocationRule` | object | yes | Revocation rule. |
| `validFrom` | RFC3339 UTC | yes | Start of validity window. |
| `validUntil` | RFC3339 UTC | yes | End of validity window; must be after `validFrom`. |
| `businessReason` | object | yes | Business reason for issuance. |

**Response (201):** `PromotionInstrument` resource.

**Domain effects:** creates or updates the account's `WalletAccount`, credits the
matching available balance, and emits `BenefitIssued`.

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Benefit

**GET** `/api/v1/benefits/{benefitId}`

**Response (200):** `PromotionInstrument` resource.

**Error codes:** `NOT_FOUND`

### List Benefits by Account

**GET** `/api/v1/benefits?byAccountId={accountId}&limit=20&offset=0`

`byAccountId` is required and contains the owning `accountId`. Broad unfiltered
listing is not part of this activation-wave API.

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
| `businessReason` | object | yes | Business reason for freezing value. |

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
| `businessReason` | object | yes | Business reason. The same benefit and the same reason cannot be redeemed twice. |

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

The following domain command from `docs/02-domains/wallet-promotion.md` has no
public HTTP endpoint in this activation wave:

- `ExpireBenefit` — triggered by the benefit `validUntil` scheduler/domain
  policy. It moves eligible active benefits to `EXPIRED`, zeros their remaining
  available/reserved ledger exposure without making balances negative, carries a
  `businessReason.reasonType` of `SYSTEM_EXPIRY`, and emits `BenefitExpired`.
