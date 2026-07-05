# Risk & Compliance — HTTP API

Last updated: 2026-07-05

## Overview

Risk & Compliance performs risk assessments for orders, payments, post-sales
requests, and account actions. It also issues challenges when additional
verification is needed.

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

## Bus-only commands

- `IssueChallenge` (internal: triggered by `CHALLENGE` decision)

## Open Issues

- None.
