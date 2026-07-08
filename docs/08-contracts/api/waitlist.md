# Waitlist — HTTP API

Last updated: 2026-07-08

## Overview

Waitlist manages candidate requests for unavailable or insufficient-capacity
travel segments. This contract is scoped to the first activation wave for the
future-scope Waitlist domain and is bounded by `docs/02-domains/waitlist.md`.

Activation-wave rulings:

- Fulfillment reuses the normal booking chain. When Waitlist consumes a matching
  `CapacityReleased` event for the head of the queue, it prices at current fare
  and creates a commercial offer before creating the order:
  `POST /api/v1/fare-quotes` with idempotency key
  a persisted UUID-v7 idempotency key, then `POST /api/v1/offers` with a
  persisted UUID-v7 key and the candidate Trip Planning `itineraryRef`, then
  `POST /api/v1/journey-orders` with a persisted UUID-v7 key and the real
  `offerId`. The existing
  journey-order saga owns booking, capacity hold, payment capture, and
  ticketing. Waitlist then consumes `JourneyOrderConfirmed` and
  `JourneyOrderCancelled` to move the request to `FULFILLED` or back to
  `QUEUED`.
- Direct `AuthorizeWaitlistHold` calls to Capacity & Availability are a future
  note only in this activation wave. Capacity & Availability contracts are not
  changed by Waitlist.
- Payment guarantee is represented by the required reference field
  `paymentGuaranteeRef`. The value MUST either start with `pay-auth-` or be an
  existing `paymentIntentId` (`pi-<uuid>`). Real pre-authorization closure is
  deferred to later wallet-promotion/payment work.
- Mutual-exclusion invariant: for the same `(travelerRef, intentFingerprint)`,
  at most one active request may exist. Active statuses are `DRAFT`, `QUEUED`,
  `MATCHING`, and `SUSPENDED`.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, and pagination conventions. All timestamps
are RFC3339 UTC. JSON fields are camelCase and enum values are
SCREAMING_SNAKE_CASE.

## Status enum

The wire status enum is the 8-state machine from the Waitlist domain document:

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `DRAFT` | User is selecting or confirming the waitlist plan. | `QUEUED`, `CANCELLED` |
| `QUEUED` | Request is in the waitlist queue. | `MATCHING`, `EXPIRED`, `CANCELLED`, `SUSPENDED` |
| `MATCHING` | Released capacity was observed and Waitlist is attempting fulfillment through the normal journey-order chain. | `FULFILLED`, `QUEUED` |
| `FULFILLED` | Request was successfully fulfilled and entered booking through journey-order. | `CLOSED` |
| `EXPIRED` | Deadline elapsed before fulfillment. | `CLOSED` |
| `CANCELLED` | User or business cancellation. | `CLOSED` |
| `SUSPENDED` | Payment guarantee, risk, or data issue paused the request. | `QUEUED`, `CANCELLED` |
| `CLOSED` | Terminal archival state. | - |

`FULFILLED` and `EXPIRED` rest observable via GET; `CLOSED` is reached from
them only by a future archival sweep (not yet implemented). `CANCELLED`
closes immediately after the cancellation response.

No `FAILED` status is exposed in this contract; a failed matching attempt rolls
back to `QUEUED` when the associated journey order is cancelled.

## Resource representation

`WaitlistRequest` responses use the following fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Canonical waitlist request ID (`wlr-<uuid>`). |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | string | yes | Requested service segment reference. |
| `travelClass` | string | no | Requested travel class/seat class when specified by the candidate plan. |
| `deadline` | RFC3339 UTC | yes | Latest time the request may be fulfilled. |
| `paymentGuaranteeRef` | string | yes | Payment guarantee reference; MUST start with `pay-auth-` or be an existing `paymentIntentId` (`pi-<uuid>`). |
| `itineraryRef` | string | yes | Candidate Trip Planning itinerary reference captured at waitlist creation; used to create the fulfillment offer at current price. |
| `intentFingerprint` | string | yes | Stable fingerprint of the mutually exclusive travel intent. Used with `travelerRef` to enforce the active-request invariant. |
| `status` | enum | yes | One of `DRAFT`, `QUEUED`, `MATCHING`, `FULFILLED`, `EXPIRED`, `CANCELLED`, `SUSPENDED`, `CLOSED`. |
| `journeyOrderRef` | string | no | Fulfilled journey order reference after Journey Order confirms the order. |

## Endpoints

### Create Waitlist Request

**POST** `/api/v1/waitlist-requests`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Replays with the same body
return the original response. Reusing the same key with a different body returns
`IDEMPOTENCY_KEY_REUSED`.

The request creates a WaitlistRequest from the user-confirmed candidate plan. In
this activation wave the service validates `paymentGuaranteeRef` as a reference
only; it does not call Payment to create or close a real pre-authorization.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Account that will own the fulfilled journey order. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | string | yes | Requested segment reference. |
| `travelClass` | string | no | Requested travel class/seat class. |
| `deadline` | RFC3339 UTC | yes | Latest fulfillment time; must be in the future at creation. |
| `paymentGuaranteeRef` | string | yes | Required guarantee reference; MUST start with `pay-auth-` or be an existing `paymentIntentId` (`pi-<uuid>`). |
| `itineraryRef` | string | yes | Candidate Trip Planning itinerary reference to use when creating the fulfillment offer. |
| `intentFingerprint` | string | yes | Stable mutual-exclusion key for the requested travel intent. |

**Response (201):** `WaitlistRequest` resource.

**Domain effects:** emits `WaitlistRequestCreated`, then internally proceeds to
payment-guarantee reference validation and queueing
(`WaitlistPaymentAuthorizationRequested`, `WaitlistQueued`) when the request can
be queued.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `CONFLICT` is returned when `(travelerRef, intentFingerprint)` already has an
  active request (`DRAFT`, `QUEUED`, `MATCHING`, or `SUSPENDED`).
- `DOMAIN_RULE_VIOLATION` is returned when the deadline or payment guarantee
  reference violates Waitlist invariants.

### Get Waitlist Request

**GET** `/api/v1/waitlist-requests/{waitlistRequestId}`

**Response (200):** `WaitlistRequest` resource.

**Error codes:** `NOT_FOUND`

### List Waitlist Requests by Traveler

**GET** `/api/v1/waitlist-requests?travelerRef={travelerRef}&limit=20&offset=0`

`travelerRef` is required for list queries. Broad unfiltered listing is not part
of this activation-wave API.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `status` | enum | no | Optional status filter using the 8-state enum above. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `WaitlistRequest` resource.

**Error codes:** `VALIDATION_FAILED`

### Cancel Waitlist Request

**POST** `/api/v1/waitlist-requests/{waitlistRequestId}/cancel`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | User or business cancellation reason. Do not include unmasked documents or other sensitive personal data. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `waitlistRequestId` | string | Waitlist request ID. |
| `status` | enum | `CANCELLED` |
| `cancelledAt` | RFC3339 UTC | Cancellation timestamp. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`

- `PRECONDITION_FAILED` is returned when the request is already terminal
  (`FULFILLED`, `EXPIRED`, `CANCELLED`, or `CLOSED`) and cannot be cancelled.

## Bus-only behavior

The following domain commands from `docs/02-domains/waitlist.md` have no public
HTTP endpoint in this activation wave:

- `AuthorizeWaitlistPayment` — validates/records the required
  `paymentGuaranteeRef`; no Payment call is made in this wave.
- `EnqueueWaitlist` — places a valid request into its queue partition.
- `MatchReleasedCapacity` — triggered by consuming `CapacityReleased` from
  `events:capacity-availability`.
- `AuthorizeWaitlistHold` — future note only; direct Capacity command is not
  implemented in this wave.
- `FulfillWaitlist` — triggered by consuming `JourneyOrderConfirmed` from
  `events:journey-order` for the order started by Waitlist.
- `ExpireWaitlist` — triggered by the request `deadline`.
