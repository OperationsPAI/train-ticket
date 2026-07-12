# Fare & Pricing — HTTP API

Last updated: 2026-07-06

## Overview

Fare & Pricing computes fare quotes, price breakdowns, and adjustment quotes
(refund/change fees). It is the authoritative source for all monetary
calculations.

Field shapes reference docs/08-contracts/shared-primitives.md for Money,
timestamps, and cross-context IDs.

## Endpoints

### Create Fare Rule Set

**POST** `/api/v1/fare-rule-sets`

**Idempotency:** REQUIRED. Reusing an idempotency key with the same body returns
the original `DRAFT` response; reusing it with a different body is rejected.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `supplierId` | string | yes | Supplier identifier. |
| `contractId` | string | yes | Supplier contract identifier. |
| `productCode` | string | yes | Product/scope code. |
| `mode` | string | yes | Transport mode. |
| `channel` | string | yes | Sales channel. |
| `version` | string | yes | Supplier rule version. |
| `effectiveWindow` | object | yes | `{startsAt, endsAt}` RFC3339 timestamps. |
| `rules` | object[] | yes | Rule objects: `ruleId`, `kind`, `amount`, `explanation`, `refundable`. |

`kind` MUST be one of the domain `RuleKind` values: `base_fare`, `tax`, `fee`,
`discount`, `refund_fee`, `change_fee`. `amount` uses shared `Money` shape with
integer `minorUnits`. `explanation` is `{code, parameters}`.

**Response (201):** Full fare rule set with `ruleSetId`, `status=DRAFT`, and the
submitted fields.

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

### Publish Fare Rule Set

**POST** `/api/v1/fare-rule-sets/{ruleSetId}/publish`

**Idempotency:** REQUIRED.

Publishes the `DRAFT` rule set. Any older `PUBLISHED` rule set with the same
`channel` + `productCode` is marked `SUPERSEDED`. The service publishes
`FareRuleSetPublished` and, when applicable, `FareRuleSetSuperseded` events per
`docs/08-contracts/events/fare-pricing.md`.

**Response (200):** Full fare rule set with `status=PUBLISHED` and
`publishedAt`.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

### Compute Fare Quote

**POST** `/api/v1/fare-quotes`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerRefs` | string[] | yes | Traveler references (`tvl-<uuid>`). |
| `channel` | string | yes | Sales channel. |
| `segmentRefs` | string[] | yes | Service segment references. |
| `productCode` | string | no | RULING (2026-07-07): product to price; defaults to `rail-standard`. Rule-set selection is by `channel + productCode` (newest published, in window) — matching the publish/supersede scope. |
| `fareRuleRefs` | string[] | no | Specific fare rules to apply. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `quoteId` | string | Canonical quote ID (`fq-<uuid>`). |
| `status` | enum | `QUOTED`, `FAILED` |
| `breakdown` | object | Price breakdown (if `QUOTED`). |
| `ruleSnapshot` | object | Snapshot of rules used (if `QUOTED`). |
| `validFrom` | timestamp | Quote validity start. |
| `validUntil` | timestamp | Quote validity end. |
| `failedReason` | string | Failure reason (if `FAILED`). |

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

### Compute Adjustment Quote (Refund/Change)

**POST** `/api/v1/adjustment-quotes`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `purpose` | enum | yes | `REFUND`, `CHANGE` |
| `entitlementIds` | string[] | yes | Entitlements to adjust. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `segmentRefs` | string[] | yes | Segment references of the entitlements being adjusted (the caller — post-sales — holds these from the entitlement records). Used to resolve the original fare quote; unresolvable refs -> 412 PRECONDITION_FAILED. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `adjustmentQuoteId` | string | Adjustment quote ID. |
| `purpose` | enum | As requested. |
| `status` | enum | `QUOTED`, `FAILED` |
| `refundableAmount` | object | Money amount refundable to customer. |
| `amountDue` | object | Money amount due from customer. |
| `validUntil` | timestamp | Adjustment quote expiry. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`

### Get Fare Quote

**GET** `/api/v1/fare-quotes/{quoteId}`

**Response (200):** Full quote details.

**Error codes:** `NOT_FOUND`

## Reliability ruling (2026-07-07, updated Wave-10)

Fare-pricing now follows `docs/08-contracts/persistence.md` transactional
outbox semantics. HTTP success means the state change and its outbox rows
committed in one PostgreSQL transaction; Redis delivery is asynchronous and
at-least-once through the outbox relay ordered by `outbox.seq`. Rule-set
publish/supersede writes `FareRuleSetPublished` and
`FareRuleSetSuperseded` envelopes into the same transaction as publishing the
new rule set and superseding older matching rule sets. Event ids remain
deterministic from `(ruleSetId, version, action)`, so relay retries and
duplicate stream deliveries are safe with durable consumer dedup.

The former phase-1 "publish failure -> rollback + 503" paragraph is
superseded by `persistence.md`: after a service adopts the transactional
outbox there is no publish-failure HTTP path; rollback/503 applies only to
database transaction failure.

## Open Issues

- None.

## ADR-0003 wave A identity-verification increment (需同波实现)

Fare & Pricing MUST query Identity Verification's read-only eligibility-certificate endpoint before applying student, child, or military-disabled discount rules. This is a same-wave implementation requirement, not a docs-only dependency.

| Touchpoint | Required change |
|---|---|
| Fare quote application service / outbound adapter | Call `GET /api/v1/identity-verification/eligibility-certificates` with `travelerId`, optional `eligibilityType`, `journeyDate`, and `productCode` derived from the fare quote request. |
| Discount-rule validation | Treat absent, expired, revoked, rejected, exhausted, or unavailable certificates as ineligible facts for the relevant discount. Fare & Pricing must not reserve or confirm annual usage. |
| Enum mapping | Map `STUDENT`, `CHILD`, and `MILITARY_DISABLED` to local discount categories. Do not add new Money fields; all monetary outputs remain `{currency, minorUnits}`. |
| Error handling | `UNAVAILABLE` from Identity Verification degrades/blocks discount evaluation according to fare policy; it must not be converted into a fabricated eligible certificate. |

The queried endpoint is read-only and returns certificate facts only. Identity Verification does not compute fare amounts, and Fare & Pricing does not emit identity-verification events.
