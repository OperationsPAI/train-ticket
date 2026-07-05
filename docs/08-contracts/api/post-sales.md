# Post Sales — HTTP API

Last updated: 2026-07-05

## Overview

Post Sales manages post-sales cases: cancellations, refunds, changes, and
compensation. It evaluates eligibility, approves or rejects cases, and
orchestrates downstream actions (entitlement voiding, refunds).

## Endpoints

### Open Post-Sales Case

**POST** `/api/v1/post-sales-cases`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | string | yes | Parent order ID (`ord-<uuid>`). |
| `caseType` | enum | yes | `CANCELLATION`, `REFUND`, `CHANGE`, `REBOOK`, `COMPENSATION` |
| `scope` | object | yes | Affected order items, segments, travelers, entitlements. |
| `reasonCode` | string | yes | Business reason for the case. |
| `actorRef` | string | yes | Who requested the case (account ID or operator ID). |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Canonical case ID (`psc-<uuid>`). |
| `journeyOrderId` | string | Parent order. |
| `caseType` | enum | Case type. |
| `status` | enum | `OPENED`, `EVALUATING`, `APPROVED`, `REJECTED`, `APPLIED`, `FAILED` |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`

### Evaluate Post-Sales Eligibility

**POST** `/api/v1/post-sales-cases/{caseId}/evaluate`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Case ID. |
| `eligible` | boolean | Whether the request is eligible. |
| `adjustmentQuoteId` | string | Reference to fare-pricing adjustment quote. |
| `refundableAmount` | object | Amount refundable (Money). |
| `amountDue` | object | Amount due (Money). |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `UNAVAILABLE`

### Approve Post-Sales Case

**POST** `/api/v1/post-sales-cases/{caseId}/approve`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Case ID. |
| `status` | enum | `APPROVED` |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

### Get Post-Sales Case

**GET** `/api/v1/post-sales-cases/{caseId}`

**Response (200):** Full case details.

**Error codes:** `NOT_FOUND`

## Open Issues

- None.
