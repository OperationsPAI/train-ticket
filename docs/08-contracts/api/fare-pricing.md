# Fare & Pricing — HTTP API

Last updated: 2026-07-05

## Overview

Fare & Pricing computes fare quotes, price breakdowns, and adjustment quotes
(refund/change fees). It is the authoritative source for all monetary
calculations.

Field shapes reference docs/08-contracts/shared-primitives.md for Money,
timestamps, and cross-context IDs.

## Endpoints

### Compute Fare Quote

**POST** `/api/v1/fare-quotes`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerRefs` | string[] | yes | Traveler references (`tvl-<uuid>`). |
| `channel` | string | yes | Sales channel. |
| `segmentRefs` | string[] | yes | Service segment references. |
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

## Open Issues

- None.
