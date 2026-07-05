# Journey Order — HTTP API

Last updated: 2026-07-05

## Overview

Journey Order is the commercial order aggregate. It manages order creation,
state transitions (pending payment, confirmed, adjusted), and order queries.
It references but does not own payments, capacity, or entitlements.

## Endpoints

### Create Journey Order

**POST** `/api/v1/journey-orders`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Owning account. |
| `offerId` | string | yes | Source offer ID (`off-<uuid>`). |
| `offerVersion` | integer | yes | Version of the offer at quote time. |
| `travelerRefs` | array | yes | Traveler references (`tvl-<uuid>`). |
| `segmentRefs` | string[] | yes | Segment references. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Canonical order ID (`ord-<uuid>`). |
| `accountId` | string | Owning account. |
| `offerId` | string | Source offer ID. |
| `monetarySummary` | object | Price breakdown at creation. |
| `status` | enum | `CREATED`, `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `ADJUSTED` |
| `travelerRefs` | array | Traveler references. |
| `segmentRefs` | string[] | Segment references. |
| `createdAt` | timestamp | Order creation time. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND` (offerId), `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`

### Get Journey Order

**GET** `/api/v1/journey-orders/{orderId}`

**Response (200):** Full order details including status, monetary summary, timestamps.

**Error codes:** `NOT_FOUND`

### List Journey Orders

**GET** `/api/v1/journey-orders?accountId={accountId}&limit=20&offset=0&status=CONFIRMED`

**Query parameters:** `accountId` (optional filter), `status` (optional filter), `limit`, `offset`

**Response (200):** Paginated response with `items`, `total`, `limit`, `offset`.

### Cancel Journey Order

**POST** `/api/v1/journey-orders/{orderId}/cancel`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Cancellation reason. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Order ID. |
| `status` | enum | `CANCELLED` |
| `cancelledAt` | timestamp | Cancellation time. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`

## Bus-only commands

- `MarkPendingPayment` (internal: triggered by booking saga)
- `ConfirmJourneyOrder` (internal: triggered by booking saga)

## Open Issues

- None.
