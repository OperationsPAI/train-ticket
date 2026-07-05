# Payment — HTTP API

Last updated: 2026-07-05

## Overview

Payment manages payment intents, authorization, capture, and refunds. It is
the authoritative source for all payment transactions. Payment events do not
directly modify order or entitlement state.

Field shapes reference docs/08-contracts/shared-primitives.md for Money,
timestamps, and cross-context IDs.

## Endpoints

### Create Payment Intent

**POST** `/api/v1/payment-intents`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `businessRef` | string | yes | Reference to the business object (order ID). |
| `purpose` | string | yes | Payment purpose (e.g. `purchase`). |
| `amount` | object | yes | Amount to collect (Money — see shared-primitives.md). |
| `payerRef` | string | yes | Payer identifier. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `paymentIntentId` | string | Canonical payment intent ID (`pi-<uuid>`). |
| `businessRef` | string | Business reference. |
| `amount` | object | Amount (Money). |
| `status` | enum | `CREATED`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `CANCELLED` |
| `createdAt` | timestamp | Creation timestamp. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`

### Cancel Payment Intent

**POST** `/api/v1/payment-intents/{paymentIntentId}/cancel`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Reason for cancellation. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `paymentIntentId` | string | Payment intent ID. |
| `status` | enum | `CANCELLED` |
| `cancelledAt` | timestamp | When cancellation was recorded. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`

### Capture Payment

**POST** `/api/v1/payment-intents/{paymentIntentId}/capture`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `paymentIntentId` | string | Payment intent ID. |
| `status` | enum | `CAPTURED` |
| `capturedAmount` | object | Captured amount (Money). |
| `channelTransactionId` | string | Channel transaction reference. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `UNAVAILABLE`

### Request Refund

**POST** `/api/v1/refunds`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | string | yes | Original payment intent ID. |
| `amount` | object | yes | Amount to refund (Money). |
| `reason` | string | yes | Refund reason. |
| `businessCaseRef` | string | no | Reference to post-sales case that authorized the refund. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `refundId` | string | Canonical refund ID. |
| `paymentIntentId` | string | Original payment intent. |
| `amount` | object | Refund amount (Money). |
| `status` | enum | `REQUESTED`, `SETTLED`, `FAILED` |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`

### Get Payment Intent

**GET** `/api/v1/payment-intents/{paymentIntentId}`

**Response (200):** Full payment intent details.

**Error codes:** `NOT_FOUND`

### Get Refund

**GET** `/api/v1/refunds/{refundId}`

**Response (200):** Full refund details.

**Error codes:** `NOT_FOUND`

## Bus-only commands

- `SettleRefund` (internal: triggered by channel callback)

## Open Issues

- None.
