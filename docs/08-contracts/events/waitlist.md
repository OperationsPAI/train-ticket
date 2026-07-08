# Waitlist — Events & Commands

Last updated: 2026-07-08

## Scope and activation-wave rulings

This contract enumerates exactly the Waitlist events listed in
`docs/02-domains/waitlist.md` and does not add extra domain events.

Activation-wave rulings:

- Fulfillment reuses the normal journey-order chain. Waitlist consumes
  `CapacityReleased` for a matching head-of-queue request, calls the normal
  quote→offer→order chain (`POST /api/v1/fare-quotes` with
  `wl-quote:{waitlistRequestId}`, `POST /api/v1/offers` with
  `wl-offer:{waitlistRequestId}`, and `POST /api/v1/journey-orders` with
  `wl-fulfill:{waitlistRequestId}`), and then consumes `JourneyOrderConfirmed`
  or `JourneyOrderCancelled` to advance to `FULFILLED` or return to `QUEUED`.
  Fulfillment is at the current quoted fare at match time.
- `AuthorizeWaitlistHold` and `WaitlistHoldAuthorized` are documented because
  they are in the domain command/event table, but direct Capacity & Availability
  hold authorization is future-scope for this activation wave. Capacity &
  Availability publishes no new Waitlist-specific events and accepts no new
  Waitlist command in this wave.
- Payment guarantee is a required reference field, `paymentGuaranteeRef`, whose
  value starts with `pay-auth-` or is an existing `paymentIntentId` (`pi-<uuid>`).
  Real pre-authorization closure is deferred to later payment work.
- Mutual exclusion is enforced by Waitlist: the same
  `(travelerRef, intentFingerprint)` may have at most one active request in
  `DRAFT`, `QUEUED`, `MATCHING`, or `SUSPENDED`.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE, and
all timestamps are RFC3339 UTC. Envelope fields, including optional trace
context propagation, follow `docs/08-contracts/messaging.md` and
`docs/08-contracts/shared-primitives.md`.

## Event identity and idempotency

Waitlist producers MUST assign deterministic event IDs per aggregate transition
so that retries do not create duplicate facts. The event ID seed is:

```
waitlist:<eventType>:<waitlistRequestId>:<aggregateVersion>
```

where `aggregateVersion` is the version after applying the transition. If the
same command or consumed upstream event is replayed, the same transition emits
the same `eventId`; if no new transition is applied, no new event is emitted.
Consumers still deduplicate by envelope `eventId`.

## Status enum

Waitlist event payloads use the same 8-state enum as the HTTP API:
`DRAFT`, `QUEUED`, `MATCHING`, `FULFILLED`, `EXPIRED`, `CANCELLED`,
`SUSPENDED`, `CLOSED`.

## Published Events

### WaitlistRequestCreated

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | reporting |
| **Trigger** | `CreateWaitlistRequest` command accepted for a user-confirmed waitlist candidate plan. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Canonical waitlist request ID (`wlr-<uuid>`). |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | string | yes | Requested segment reference. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `deadline` | RFC3339 UTC | yes | Latest fulfillment time. |
| `paymentGuaranteeRef` | string | yes | Required guarantee reference (`pay-auth-*` or `pi-<uuid>`). |
| `intentFingerprint` | string | yes | Stable mutual-exclusion key for the travel intent. |
| `status` | enum | yes | `DRAFT`. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |

### WaitlistPaymentAuthorizationRequested

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | reporting |
| **Trigger** | `AuthorizeWaitlistPayment` command records the required payment guarantee reference. In this activation wave this is reference validation only, not a Payment pre-authorization call. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `paymentGuaranteeRef` | string | yes | Guarantee reference (`pay-auth-*` or `pi-<uuid>`). |
| `requestedAt` | RFC3339 UTC | yes | Time the guarantee reference was recorded for the request. |
| `status` | enum | yes | Current request status after recording the reference; normally `DRAFT` before queueing or `SUSPENDED` if validation cannot proceed. |

### WaitlistQueued

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | notification, reporting |
| **Trigger** | `EnqueueWaitlist` command places a valid request into its queue partition, or a previously matching request returns to the queue after the associated journey order is cancelled. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Segment whose queue partition contains the request. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `intentFingerprint` | string | yes | Mutual-exclusion key. |
| `queuedAt` | RFC3339 UTC | yes | Time the request entered or re-entered the queue. |
| `status` | enum | yes | `QUEUED`. |
| `journeyOrderRef` | string | no | Associated `ord-<uuid>` when re-queueing after `JourneyOrderCancelled`. |
| `requeueReason` | string | no | Reason when re-queueing after a fulfillment-chain domain rejection before an order exists. |

### WaitlistMatchStarted

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | reporting |
| **Trigger** | `MatchReleasedCapacity` command consumes a matching `CapacityReleased` event for the head of the queue and starts fulfillment through the normal journey-order chain. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Segment being matched. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `matchedCapacityReleaseRef` | string | yes | Envelope `eventId` of the consumed `CapacityReleased` fact that triggered matching. |
| `journeyOrderIdempotencyKey` | string | yes | Deterministic idempotency key used when Waitlist posts to `POST /api/v1/journey-orders`. |
| `startedAt` | RFC3339 UTC | yes | Matching start timestamp. |
| `status` | enum | yes | `MATCHING`. |

### WaitlistHoldAuthorized

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | reporting |
| **Trigger** | `AuthorizeWaitlistHold` command succeeds. Future-scope in this activation wave; direct Capacity & Availability hold authorization is not produced now. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Segment for which a waitlist hold was authorized. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `authorizedAt` | RFC3339 UTC | yes | Hold authorization timestamp. |
| `status` | enum | yes | `MATCHING`. |

### WaitlistFulfilled

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | notification, reporting |
| **Trigger** | `FulfillWaitlist` command consumes `JourneyOrderConfirmed` for the journey order started by Waitlist. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Fulfilled segment reference. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `journeyOrderRef` | string | yes | Confirmed journey order ID (`ord-<uuid>`). |
| `fulfilledAt` | RFC3339 UTC | yes | Fulfillment timestamp. |
| `status` | enum | yes | `FULFILLED`. |

Notification consumes this event for the waitlist-success user touchpoint.

### WaitlistCancelled

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | notification, reporting |
| **Trigger** | `CancelWaitlist` command cancels an active waitlist request. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Cancelled segment reference. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |
| `reason` | string | yes | User or business cancellation reason; do not include unmasked documents or other sensitive personal data. |
| `status` | enum | yes | `CANCELLED`. |

Notification consumes this event for the waitlist-cancelled user touchpoint.

### WaitlistExpired

| Field | Description |
|---|---|
| **Producer** | waitlist |
| **Consumers** | notification, reporting |
| **Trigger** | `ExpireWaitlist` command runs when `deadline` is reached before fulfillment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `waitlistRequestId` | string | yes | Waitlist request ID. |
| `accountId` | string | yes | Account that owns fulfillment/order creation. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Expired segment reference. |
| `travelClass` | string | no | Requested travel class/seat class when specified. |
| `deadline` | RFC3339 UTC | yes | Deadline that elapsed. |
| `expiredAt` | RFC3339 UTC | yes | Expiry processing timestamp. |
| `status` | enum | yes | `EXPIRED`. |

Notification consumes this event for the waitlist-expired user touchpoint.

## Accepted Commands

The command list is the domain command/event table from
`docs/02-domains/waitlist.md`.

| Command | Sender / Trigger | Produced event |
|---|---|---|
| `CreateWaitlistRequest` | API gateway / UI via `POST /api/v1/waitlist-requests` | `WaitlistRequestCreated` |
| `AuthorizeWaitlistPayment` | Waitlist application flow after create; reference-only in this wave | `WaitlistPaymentAuthorizationRequested` |
| `EnqueueWaitlist` | Waitlist application flow after guarantee reference validation, or requeue after `JourneyOrderCancelled` | `WaitlistQueued` |
| `MatchReleasedCapacity` | Waitlist consumer of `CapacityReleased` from `events:capacity-availability` | `WaitlistMatchStarted` |
| `AuthorizeWaitlistHold` | Future-scope direct hold authorization | `WaitlistHoldAuthorized` |
| `FulfillWaitlist` | Waitlist consumer of `JourneyOrderConfirmed` from `events:journey-order` | `WaitlistFulfilled` |
| `CancelWaitlist` | API gateway / UI via `POST /api/v1/waitlist-requests/{waitlistRequestId}/cancel` or business cancellation | `WaitlistCancelled` |
| `ExpireWaitlist` | Waitlist scheduler at `deadline` | `WaitlistExpired` |

## Consumed upstream events

| Upstream stream | Event type | Purpose |
|---|---|---|
| `events:capacity-availability` | `CapacityReleased` | Find a matching head-of-queue request and start normal journey-order fulfillment. |
| `events:journey-order` | `JourneyOrderConfirmed` | Mark the matching request `FULFILLED`. |
| `events:journey-order` | `JourneyOrderCancelled` | Return the matching request to `QUEUED` instead of introducing a new failure event. |

## Notification touchpoints

Notification consumes Waitlist events only for the four touchpoint classes below;
reporting consumes all Waitlist events for metrics and audit read models.

| Touchpoint class | Event source |
|---|---|
| Success | `WaitlistFulfilled` |
| Failure / unable-to-complete matching | `WaitlistQueued` when re-queued after `JourneyOrderCancelled` |
| Expired | `WaitlistExpired` |
| Cancelled | `WaitlistCancelled` |
