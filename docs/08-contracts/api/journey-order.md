# Journey Order HTTP API

Last updated: 2026-09-28.

The deployed revision and runtime facts are recorded in
[current status](../../00-current-status.md).
Remaining work is tracked in [TODO](../../TODO.md).

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Journey Order is the commercial order aggregate. It manages order creation,
state transitions (pending payment, confirmed, adjusted), and order queries.
It references but does not own payments, capacity, entitlements, or identity-verification facts.

## Identity Verification before order creation

Journey Order calls Identity Verification before accepting `POST /api/v1/journey-orders`.
This check is deployed.
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

### Apply a Change to the Order

**POST** `/api/v1/journey-orders/{orderId}/apply-change`

Requires `X-Correlation-Id`.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Approved `CHANGE` post-sales case applied to this order. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Order ID. |
| `caseId` | string | Applied case ID. |
| `status` | enum | Resulting order status (`ADJUSTED` on a first application). |
| `offerId` | string | Replacement offer now carried by the order. |

The service reads the change case and requires an `APPROVED` `CHANGE` case for this order, verifies the replacement tickets and the voided original tickets, and checks the change settlement covers the target fare, fee and retained items before updating the order.
Repeating the same `caseId` returns the recorded result without a second `JourneyOrderPostSalesAdjusted` event.

**Error codes:** `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`

## Bus-only commands

- `MarkPendingPayment` (internal: triggered by booking saga)
- `ConfirmJourneyOrder` (internal: triggered by booking saga)

## Supply integration

The accepted purchase source endpoint is implemented and deployed.
Booking accepted supply resolution is implemented, deployed and verified, and is
active in the production integration with Capacity (T03, closed).
Capacity resolves the accepted class, service, date and interval from these
facts, validates published supply and counts pending holds with active
allocations; the source read alone is not what establishes historical occupancy.
