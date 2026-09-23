# Journey Order HTTP API

Last updated: 2026-09-23.

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Journey Order is the commercial order aggregate. It manages order creation,
state transitions (pending payment, confirmed, adjusted), and order queries.
It references but does not own payments, capacity, entitlements, or identity-verification facts.

## ADR-0003 wave A identity-verification increment (需同波实现)

Journey Order MUST perform a pre-order identity-verification hook before accepting `POST /api/v1/journey-orders`. This is a same-wave implementation requirement, not a docs-only dependency.

| Touchpoint | Required change |
|---|---|
| HTTP validator for `POST /api/v1/journey-orders` | Before aggregate creation, call `POST /api/v1/identity-verification/pre-order-checks` with `accountId`, `offerId`, `offerVersion`, `travelerRefs`, `segmentRefs`, a persisted `orderIntentId`, `journeyDate`, `productCode`, `limitPolicyVersion`, and a UUID-v7 `Idempotency-Key`. |

The order uses `productCode` and `eligibilityUsages` from the Fare Pricing quote identified by its Offer's `fareQuoteRef`.
Each eligibility usage identifies the traveler, eligibility type and certificate reserved by Identity Verification.
| Create-order idempotency | Persist and reuse the identity pre-order check idempotency key for retries of the same Journey Order create attempt. The API header remains the direct HTTP idempotency key; internal material folding is owned by Identity Verification. |
| Validation / error mapping | Map Identity Verification `REJECT` and `MANUAL_REVIEW_REQUIRED` results to the existing create-order rejection path: `PRECONDITION_FAILED` (412) for unacceptable verification state, `DOMAIN_RULE_VIOLATION` (422) for policy violations, `CONFLICT` (409) for protected-scope purchase-limit conflicts, and `UNAVAILABLE` (503) for hook/read-model unavailability. |
| Enums / state machine | Do not add a new Journey Order status for identity verification. A failed hook prevents order creation; it is not an order lifecycle transition. |

The hook response fields and error semantics are defined in `docs/08-contracts/api/identity-verification.md`. All timestamps are RFC3339 UTC; propagated event correlation IDs use `corr-<uuid-v7>` and command causation IDs use `cmd-<uuid-v7>` when the create command later emits events.

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

The response includes `travelers` with the traveler types captured by the offer and `segments` with the itinerary's actual endpoints, transport mode, departure time and arrival time.
Order creation requires the offer version, account, traveler set and segment set to match the request.
An unavailable, expired or inconsistent offer cannot create a replacement-priced order.

**Response (200):** Full order details including status, monetary summary, timestamps.

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

The accepted purchase source endpoint supports the coordinated Capacity integration described in [capacity integration requirements](../../10-domain-enrichment/capacity-overbooking.md).
A source read alone does not establish complete historical occupancy or correct replacement compensation.
