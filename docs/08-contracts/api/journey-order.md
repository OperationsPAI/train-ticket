# Journey Order HTTP API

Last updated: 2026-09-24.

Runtime baseline: Helm revision 279.
Remaining work is tracked in [TODO](../../TODO.md).

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Journey Order is the commercial order aggregate. It manages order creation,
state transitions (pending payment, confirmed, adjusted), and order queries.
It references but does not own payments, capacity, entitlements, or identity-verification facts.

## Identity Verification before order creation

Journey Order calls Identity Verification before accepting `POST /api/v1/journey-orders`.
This check is deployed in Helm revision 279.
Rejected or unresolved verification prevents order creation.

| Touchpoint | Current behavior |
|---|---|
| HTTP validator for `POST /api/v1/journey-orders` | Before aggregate creation, calls `POST /api/v1/identity-verification/pre-order-checks` using the resolved Offer, travelers, segments, stable `orderIntentId`, journey date, product and eligibility inputs, policy version and a UUID v7 `Idempotency-Key`. |
| Create-order idempotency | Derives a stable identity check key from the HTTP key and request fingerprint, reuses it on retry, and saves the accepted check reference with the order. |
| Validation / error mapping | `REJECT` and `MANUAL_REVIEW_REQUIRED` stop creation with `PRECONDITION_FAILED`; a response without `PASS` is unavailable. The adapter propagates policy and purchase limit errors through the existing API error mapping. |
| Enums / state machine | A failed check prevents order creation without adding an order lifecycle state. |

The order uses `productCode` and `eligibilityUsages` from the Fare Pricing quote identified by its Offer's `fareQuoteRef`.
Each eligibility usage identifies the traveler, eligibility type and certificate reserved by Identity Verification.

The hook response fields and error semantics are defined in `docs/08-contracts/api/identity-verification.md`.
All timestamps are RFC3339 UTC; propagated event correlation IDs use `corr-<uuid-v7>` and command causation IDs use `cmd-<uuid-v7>` when the create command later emits events.

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
| `status` | enum | The public lifecycle mapping described below. |
| `travelerRefs` | array | Traveler references. |
| `segmentRefs` | string[] | Segment references. |
| `createdAt` | timestamp | Order creation time. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND` (offerId), `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`

### Get Journey Order

**GET** `/api/v1/journey-orders/{orderId}`

The response includes `travelers` with the traveler types captured by the offer and `segments` with the itinerary's actual endpoints, transport mode, departure time and arrival time.
Order creation requires the offer version, account, traveler set and segment set to match the request.
An unavailable, expired or inconsistent offer cannot create a replacement-priced order.

**Response (200):** Full order details including status, monetary summary, timestamps.

`PENDING_CONFIRMATION` is serialized as `CREATED`, and `POST_SALES_ADJUSTED` as `ADJUSTED`.
Other lifecycle states retain their enum spelling: `DRAFT`, `PENDING_PAYMENT`, `CONFIRMING`, `CONFIRMED`, `PARTIALLY_CONFIRMED`, `IN_TRAVEL`, `COMPLETED`, `CANCELLED`, `DISRUPTED` and `FAILED`.

**Error codes:** `NOT_FOUND`

### Get accepted purchase source

**GET** `/api/v1/internal/journey-orders/{orderId}/purchase-source`

Returns the original accepted Offer from the persisted `JourneyOrderCreated` timeline fact.
The source remains unchanged when a successful change updates the order's current `offerId`.
Inventory reconciliation uses this source for an initial purchase reservation whose own immutable supply references are absent.
Replacement reservations use their recorded change's target Offer.

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Owning commercial order. |
| `accountId` | string | Account that accepted the purchase. |
| `offerId` | string | Original accepted Offer. |
| `timelineFactId` | string | Persisted creation fact supplying the Offer reference. |
| `acceptedAt` | timestamp | Timestamp recorded on the creation fact. |

This endpoint performs a read and does not emit an event or update the order.
An absent or ambiguous creation fact, or a creation fact without an Offer reference, returns `PRECONDITION_FAILED`.
An unknown order returns `NOT_FOUND`.

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

## Supply integration

The accepted purchase source endpoint is implemented and deployed.
Booking accepted supply resolution is implemented in source and remains inactive in the production integration with Capacity at Helm revision 279.
A source read alone does not establish complete historical occupancy or correct replacement compensation.
