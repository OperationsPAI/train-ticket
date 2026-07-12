# Dispatch — HTTP API

Last updated: 2026-07-08

## Overview

Dispatch manages immediate ride dispatch for non-scheduled ground transport. This
contract is scoped to the first activation wave for the future-scope Dispatch
domain and is bounded by `docs/02-domains/dispatch.md`.

Activation-wave rulings:

- Immediate external supply (driver, vehicle, ETA, and provider lifecycle) is a
  **simulation boundary** in this wave. Driver assignment and state advancement
  are driven by internal ops HTTP commands, similar to simulated payment
  operations in Payment. `provider-integration` has no contract or
  implementation change in this wave. Future provider-platform integration will
  replace the ops-driving adapter without changing the Dispatch aggregate
  invariants.
- Dispatch produces the Fulfillment handoff facts `DriverArrived`, `RideStarted`,
  and `RideEnded` according to the event contract. Fulfillment consumption is
  deferred and is not registered as an active subscription in
  `docs/08-contracts/messaging.md` for this wave.
- Post Sales and Notification consumption of Dispatch events is also deferred;
  this wave documents the event payloads but registers no Dispatch subscribers.
- Mutual-exclusion invariant: for the same
  `(riderAccountId, intentFingerprint)`, at most one active dispatch may exist.
  Active statuses are `REQUESTED`, `MATCHING`, `ASSIGNED`, `DRIVER_ARRIVING`,
  `DRIVER_ARRIVED`, `PICKED_UP`, and the transient `DRIVER_CANCELLED` status
  before automatic re-match.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, pagination conventions, and Money
(`{currency, minorUnits}`) if a referenced fare snapshot is expanded by a future
read model. All timestamps are RFC3339 UTC. JSON fields are camelCase and enum
values are SCREAMING_SNAKE_CASE.

## Status enum

The wire status enum is the domain state machine plus the `FAILED` timeout closure (activation-wave increment 2026-07-09):

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `REQUESTED` | Dispatch request was accepted. | `MATCHING`, `USER_CANCELLED`, `FAILED` |
| `MATCHING` | Dispatch is matching driver supply. | `ASSIGNED`, `USER_CANCELLED`, `FAILED` |
| `ASSIGNED` | Driver and vehicle have been assigned. | `DRIVER_ARRIVING`, `DRIVER_CANCELLED`, `USER_CANCELLED` |
| `DRIVER_ARRIVING` | Driver is en route to the pickup point. | `DRIVER_ARRIVED`, `DRIVER_CANCELLED`, `USER_CANCELLED` |
| `DRIVER_ARRIVED` | Driver has arrived and is waiting for the rider. | `PICKED_UP`, `NO_SHOW`, `USER_CANCELLED` |
| `PICKED_UP` | Rider is on board and the ride has started. | `COMPLETED` |
| `DRIVER_CANCELLED` | Driver cancelled the active assignment. | `MATCHING`, `FAILED` |
| `USER_CANCELLED` | Rider/user cancelled the dispatch. | Terminal for this API wave |
| `NO_SHOW` | Rider did not appear at pickup. | Terminal for this API wave |
| `COMPLETED` | Dispatch lifecycle completed after ride end. | Terminal for this API wave |
| `FAILED` | Request/matching window timed out (timeout scan) or re-dispatch was closed by failure policy. | Terminal for this API wave |

`DRIVER_CANCELLED` is an observable transition fact only briefly. The Dispatch
application MUST automatically return the request to `MATCHING` for re-dispatch
unless the timeout scan (or a future failure policy) closes it as `FAILED`.

## Resource representation

`RideRequest` responses use the following fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Canonical ride request ID (`rrq-<uuid>`). |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>` or the shared structured TravelerRef). |
| `pickupRef` | string | yes | Pickup place/location reference captured from Trip Planning or the UI. |
| `dropoffRef` | string | yes | Drop-off place/location reference captured from Trip Planning or the UI. |
| `timeWindow` | object | yes | Requested pickup window; see [TimeWindow](#timewindow). |
| `estimatedFareRef` | string | no | Optional Fare & Pricing estimate reference. The estimate itself remains owned by Fare & Pricing. |
| `intentFingerprint` | string | yes | Stable fingerprint of the mutually exclusive dispatch intent. |
| `status` | enum | yes | One of the 10 statuses above. |
| `assignment` | object | no | Current active assignment when a driver is assigned; see [RideAssignment](#rideassignment). |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last state-change timestamp. |

### TimeWindow

| Field | Type | Required | Description |
|---|---|---|---|
| `startAt` | RFC3339 UTC | yes | Earliest acceptable pickup time. |
| `endAt` | RFC3339 UTC | yes | Latest acceptable pickup time; must be after `startAt`. |

### RideAssignment

| Field | Type | Required | Description |
|---|---|---|---|
| `rideAssignmentId` | string | yes | Canonical assignment ID (`ras-<uuid>`). |
| `driverRef` | string | yes | Simulated or future provider driver reference. |
| `vehicleRef` | string | yes | Simulated or future provider vehicle reference. |
| `etaSeconds` | integer | yes | Current ETA to pickup in seconds; must be non-negative. |
| `assignedAt` | RFC3339 UTC | yes | Assignment timestamp. |
| `arrivedAt` | RFC3339 UTC | no | Driver arrival timestamp after `driver-arrived`. |
| `startedAt` | RFC3339 UTC | no | Ride start timestamp after `start`. |
| `endedAt` | RFC3339 UTC | no | Ride end timestamp after `complete`. |

## Endpoints

### Create Ride Request

**POST** `/api/v1/ride-requests`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Replays with the same body
return the original response. Reusing the same key with a different body returns
`IDEMPOTENCY_KEY_REUSED`.

The request creates a `RideRequest` from a user-confirmed immediate-ride intent.
In this activation wave the request enters the simulated matching flow; Dispatch
does not call Provider Integration.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `timeWindow` | object | yes | Requested pickup window with `startAt` and `endAt` RFC3339 UTC fields. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>` or shared structured TravelerRef). |
| `estimatedFareRef` | string | no | Optional Fare & Pricing estimate reference. |
| `intentFingerprint` | string | yes | Stable mutual-exclusion key for the ride intent. |

**Response (201):** `RideRequest` resource.

**Domain effects:** emits `DispatchRequested`; the application may immediately
advance the request to `MATCHING` as part of the simulated matching workflow.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `CONFLICT` is returned when `(riderAccountId, intentFingerprint)` already has
  an active dispatch.
- `DOMAIN_RULE_VIOLATION` is returned when pickup/dropoff, time window, traveler,
  or account references violate Dispatch invariants.

### Get Ride Request

**GET** `/api/v1/ride-requests/{rideRequestId}`

**Response (200):** `RideRequest` resource.

**Error codes:** `NOT_FOUND`

### List Ride Requests by Rider Account

**GET** `/api/v1/ride-requests?riderAccountId={riderAccountId}&limit=20&offset=0`

`riderAccountId` is required for list queries. Broad unfiltered listing is not
part of this activation-wave API.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `riderAccountId` | string | yes | Account that owns the dispatch requests. |
| `status` | enum | no | Optional status filter using the status enum above. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `RideRequest` resource.

**Error codes:** `VALIDATION_FAILED`

## Internal ops command endpoints

The following state-changing endpoints are internal ops commands for the
simulation boundary. They MUST NOT be exposed as public rider APIs. Every ops
POST requires `Idempotency-Key` and follows the replay/reuse behavior described
above.

### Assign Driver

**POST** `/api/v1/ride-requests/{rideRequestId}/assign`

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `driverRef` | string | yes | Simulated or future provider driver reference. |
| `vehicleRef` | string | yes | Simulated or future provider vehicle reference. |
| `etaSeconds` | integer | yes | ETA to pickup in seconds; must be non-negative. |

**Response (200):** `RideRequest` resource in `ASSIGNED` or
`DRIVER_ARRIVING` status with `assignment` populated.

**Domain effects:** command `AssignDriver`; emits `DriverAssigned`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Update ETA

**POST** `/api/v1/ride-requests/{rideRequestId}/eta`

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `etaSeconds` | integer | yes | New ETA to pickup in seconds; must be non-negative. |

**Response (200):** `RideRequest` resource with updated `assignment.etaSeconds`.

**Domain effects:** command `UpdateEta`; emits `DriverEtaUpdated`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Mark Driver Arrived

**POST** `/api/v1/ride-requests/{rideRequestId}/driver-arrived`

**Request:** empty JSON object (`{}`).

**Response (200):** `RideRequest` resource in `DRIVER_ARRIVED` status.

**Domain effects:** command `MarkDriverArrived`; emits `DriverArrived` for the
Fulfillment handoff contract. Fulfillment consumption is deferred in this wave.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Start Ride

**POST** `/api/v1/ride-requests/{rideRequestId}/start`

**Request:** empty JSON object (`{}`).

**Response (200):** `RideRequest` resource in `PICKED_UP` status.

**Domain effects:** command `StartRide`; emits `RideStarted` for the Fulfillment
handoff contract. Fulfillment consumption is deferred in this wave.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Complete Dispatch

**POST** `/api/v1/ride-requests/{rideRequestId}/complete`

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `finalFareRef` | string | no | Optional Fare & Pricing final-fare or adjustment reference. Dispatch records the reference only. |

**Response (200):** `RideRequest` resource in `COMPLETED` status.

**Domain effects:** command `CompleteDispatch`; emits `RideEnded` as the
cross-context wire event for the domain completion transition. The domain table
labels the completion fact `DispatchCompleted`; this contract uses `RideEnded`
for the Fulfillment handoff named in the Dispatch upstream/downstream table.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Driver Cancel

**POST** `/api/v1/ride-requests/{rideRequestId}/driver-cancel`

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Driver/provider cancellation reason. Do not include unmasked documents or other sensitive personal data. |

**Response (200):** `RideRequest` resource after cancellation processing. The
resource may already be back in `MATCHING` because `DRIVER_CANCELLED`
automatically re-dispatches.

**Domain effects:** command `CancelByDriver`; emits `DriverCancelled`, then the
application automatically returns the request to `MATCHING` for re-assignment
unless a future failure policy closes it.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### User Cancel

**POST** `/api/v1/ride-requests/{rideRequestId}/user-cancel`

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | User cancellation reason. Do not include unmasked documents or other sensitive personal data. |

**Response (200):** `RideRequest` resource in `USER_CANCELLED` status.

**Domain effects:** command `CancelByUser`; emits `DispatchUserCancelled`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Record No Show

**POST** `/api/v1/ride-requests/{rideRequestId}/no-show`

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | no | Operational reason or policy reference. Do not include unmasked documents or other sensitive personal data. |

**Response (200):** `RideRequest` resource in `NO_SHOW` status.

**Domain effects:** command `RecordNoShow`; emits `DispatchNoShowRecorded`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

## Bus-only behavior

No Dispatch command is bus-only in this activation wave. Upstream provider
callbacks are intentionally absent because provider integration is deferred; ops
HTTP commands simulate assignment and provider lifecycle facts.
