# Risk & Compliance — HTTP API

Last updated: 2026-07-05

## Overview

Risk & Compliance performs risk assessments for orders, payments, post-sales
requests, and account actions. It also issues challenges when additional
verification is needed.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

## Endpoints

### Assess Risk

**POST** `/api/v1/risk-assessments`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `subjectRef` | string | yes | Subject of assessment (order ID, payment intent ID, account ID). |
| `scenario` | string | yes | Risk scenario: `order_risk`, `payment_risk`, `post_sales_risk`, `account_risk` |
| `context` | object | yes | Contextual data for the assessment. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `assessmentId` | string | Canonical assessment ID (`asmt-<uuid>`). |
| `subjectRef` | string | Subject reference. |
| `scenario` | string | Risk scenario. |
| `decision` | enum | `ALLOW`, `DENY`, `CHALLENGE`, `HOLD` |
| `score` | integer | Risk score (0-1000). |
| `level` | enum | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `policyVersion` | string | Policy version used. |
| `reasonCode` | string | Machine-readable reason code. |
| `reasonExplanation` | string | Human-readable explanation. |
| `assessedAt` | timestamp | When assessment completed. |

**Error codes:** `VALIDATION_FAILED`, `UNAVAILABLE`

### Get Risk Assessment

**GET** `/api/v1/risk-assessments/{assessmentId}`

**Response (200):** Full assessment details.

**Error codes:** `NOT_FOUND`

### Lift Risk Block

**POST** `/api/v1/risk-blocks/lift`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `subjectRef` | string | yes | Blocked subject reference (order ID, payment ID, account ID). |
| `scope` | enum | yes | `ORDER`, `PAYMENT`, `ACCOUNT`. |
| `reasonCode` | string | yes | Machine-readable lift reason. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `allowId` | string | Canonical allow ID (`alw-<uuid>`). |
| `subjectRef` | string | Subject reference. |
| `scope` | enum | `ORDER`, `PAYMENT`, `ACCOUNT`. |
| `reasonCode` | string | Machine-readable lift reason. |
| `policyVersion` | string | Policy version used. |
| `evidenceRef` | string | Reference to the evidence bundle. |
| `allowedAt` | timestamp | When the block was lifted. |

**Published event:** `RiskBlockLifted` with the same payload shape as `SubjectAllowed`.

**Error codes:** `VALIDATION_FAILED`, `IDEMPOTENCY_KEY_REUSED`, `NOT_FOUND`, `UNAVAILABLE`

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `IssueChallenge` | Risk system (internal) | Issue a challenge when the risk decision is `CHALLENGE`. |
| `ResolveChallenge` | Customer / Operator | Resolve a challenge with outcome (PASSED/FAILED). |
| `BlockSubject` | Risk system (internal) | Block a subject (order, payment, account) from proceeding. |
| `AllowSubject` | Risk system (internal) | Allow a previously blocked subject to proceed. HTTP lift is available for operator-driven ORDER blocks. |
| `RecordEvidence` | Risk system (internal) | Record risk evidence for audit trail. |

## Open Issues

- None.
