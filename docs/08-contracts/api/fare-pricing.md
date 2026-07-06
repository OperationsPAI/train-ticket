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

## Reliability ruling (2026-07-07)

Rule-set publish/supersede facts must not be lost to a publish failure:
if emitting `FareRuleSetPublished`/`FareRuleSetSuperseded` fails, the state
transition is rolled back and the command returns 503 UNAVAILABLE for the
caller to retry. Event ids derive deterministically from
(ruleSetId, version, action) so a retried publish re-emits identical facts
(consumer-side eventId dedup makes replay safe). This is the phase-1
answer to publish-before-commit gaps; a durable outbox is future scope.

## Open Issues

- None.
