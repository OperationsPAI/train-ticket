# Dispatch — Events & Commands

Last updated: 2026-07-08

## Scope and activation-wave rulings

This contract enumerates exactly the Dispatch command/event lifecycle from
`docs/02-domains/dispatch.md`: `RideRequest` and `RideAssignment`, the 10-state
machine, nine commands, and nine produced lifecycle facts. The completion fact is
published on the wire as `RideEnded` so Fulfillment can consume the handoff event
named in the Dispatch downstream table; it is the wire contract for the domain
`DispatchCompleted` transition.

Activation-wave rulings:

- Immediate driver/vehicle/ETA supply is a **simulation boundary**. Driver
  assignment and lifecycle advancement are driven by internal ops HTTP commands
  under `/api/v1/ride-requests/{rideRequestId}/...`. Dispatch does not consume
  provider-platform events and `provider-integration` has no contract change in
  this wave. Future external platform integration may replace the ops driver
  without changing these event payloads.
- Dispatch produces `DriverArrived`, `RideStarted`, and `RideEnded` as
  Fulfillment handoff facts. Fulfillment consumption is deferred in this wave.
- Post Sales and Notification consumption is also deferred. The events below
  identify intended touchpoints, but `docs/08-contracts/messaging.md` registers
  no active Dispatch subscribers for this activation wave.
- Mutual exclusion is enforced by Dispatch: the same
  `(riderAccountId, intentFingerprint)` may have at most one active dispatch in
  `REQUESTED`, `MATCHING`, `ASSIGNED`, `DRIVER_ARRIVING`, `DRIVER_ARRIVED`,
  `PICKED_UP`, or transient `DRIVER_CANCELLED` before automatic re-match.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE, and
all timestamps are RFC3339 UTC. Envelope fields, including optional trace context
propagation, follow `docs/08-contracts/messaging.md` and
`docs/08-contracts/shared-primitives.md`.

## Event identity and idempotency

Dispatch producers MUST assign deterministic event IDs per aggregate transition
so retries do not create duplicate facts. The event ID seed is:

```
dispatch:<eventType>:<rideRequestId>:<aggregateVersion>
```

where `aggregateVersion` is the version after applying the transition. If the
same command is replayed, the same transition emits the same `eventId`; if no new
transition is applied, no new event is emitted. Consumers still deduplicate by
envelope `eventId`.

## Status enum

Dispatch event payloads use the same enum as the HTTP API:
`REQUESTED`, `MATCHING`, `ASSIGNED`, `DRIVER_ARRIVING`, `DRIVER_ARRIVED`,
`PICKED_UP`, `DRIVER_CANCELLED`, `USER_CANCELLED`, `NO_SHOW`, `COMPLETED`,
`FAILED`.

`DRIVER_CANCELLED` MUST be followed by automatic return to `MATCHING` for
re-dispatch unless the timeout scan (or a future failure policy) closes the
request as `FAILED`.

## Published Events

### DispatchRequested

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | `RequestDispatch` command accepted from `POST /api/v1/ride-requests`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Canonical ride request ID (`rrq-<uuid>`). |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>` or shared structured TravelerRef). |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `timeWindow` | object | yes | Requested pickup window with `startAt` and `endAt` RFC3339 UTC fields. |
| `estimatedFareRef` | string | no | Fare & Pricing estimate reference, if supplied. |
| `intentFingerprint` | string | yes | Stable mutual-exclusion key for the ride intent. |
| `status` | enum | yes | `REQUESTED`. |
| `requestedAt` | RFC3339 UTC | yes | Request creation timestamp. |

### DriverAssigned

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | `AssignDriver` ops command binds a driver/vehicle assignment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Active assignment ID (`ras-<uuid>`). |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `driverRef` | string | yes | Simulated or future provider driver reference. |
| `vehicleRef` | string | yes | Simulated or future provider vehicle reference. |
| `etaSeconds` | integer | yes | ETA to pickup in seconds; non-negative. |
| `assignedAt` | RFC3339 UTC | yes | Assignment timestamp. |
| `status` | enum | yes | `ASSIGNED` or `DRIVER_ARRIVING` when the implementation immediately marks the driver en route. |

### DriverEtaUpdated

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | `UpdateEta` ops command updates the active assignment ETA. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Active assignment ID. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `driverRef` | string | yes | Driver reference. |
| `vehicleRef` | string | yes | Vehicle reference. |
| `etaSeconds` | integer | yes | Updated ETA to pickup in seconds; non-negative. |
| `updatedAt` | RFC3339 UTC | yes | ETA update timestamp. |
| `status` | enum | yes | Current status, normally `DRIVER_ARRIVING` or `ASSIGNED`. |

### DriverArrived

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: fulfillment, notification, reporting |
| **Trigger** | `MarkDriverArrived` ops command records driver arrival at pickup. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Active assignment ID. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `driverRef` | string | yes | Driver reference. |
| `vehicleRef` | string | yes | Vehicle reference. |
| `arrivedAt` | RFC3339 UTC | yes | Driver arrival timestamp. |
| `status` | enum | yes | `DRIVER_ARRIVED`. |

This event is the first Fulfillment handoff fact, but Fulfillment consumption is
deferred in this wave.

### RideStarted

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: fulfillment, notification, reporting |
| **Trigger** | `StartRide` ops command records rider pickup / ride start. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Active assignment ID. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `driverRef` | string | yes | Driver reference. |
| `vehicleRef` | string | yes | Vehicle reference. |
| `startedAt` | RFC3339 UTC | yes | Ride start timestamp. |
| `status` | enum | yes | `PICKED_UP`. |

This event is a Fulfillment handoff fact, but Fulfillment consumption is deferred
in this wave.

### RideEnded

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: fulfillment, post-sales, notification, reporting |
| **Trigger** | `CompleteDispatch` ops command records ride completion. This is the wire event for the domain `DispatchCompleted` fact. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Completed assignment ID. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `driverRef` | string | yes | Driver reference. |
| `vehicleRef` | string | yes | Vehicle reference. |
| `startedAt` | RFC3339 UTC | yes | Ride start timestamp. |
| `endedAt` | RFC3339 UTC | yes | Ride end timestamp. |
| `finalFareRef` | string | no | Optional final fare or adjustment reference recorded by Dispatch. |
| `status` | enum | yes | `COMPLETED`. |

This event is the final Fulfillment handoff fact. Fulfillment and Post Sales
consumption is deferred in this wave.

### DriverCancelled

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | `CancelByDriver` ops command records driver/provider cancellation of the active assignment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Cancelled assignment ID. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `driverRef` | string | yes | Driver reference. |
| `vehicleRef` | string | yes | Vehicle reference. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |
| `reason` | string | yes | Cancellation reason; do not include unmasked documents or other sensitive personal data. |
| `status` | enum | yes | `DRIVER_CANCELLED`. |
| `nextStatus` | enum | yes | `MATCHING`, because this wave automatically re-dispatches after driver cancellation. |

Dispatch MUST clear the cancelled active assignment and re-enter `MATCHING` after
publishing this fact unless a future failure policy closes the request.

### DispatchUserCancelled

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: post-sales, notification, reporting |
| **Trigger** | `CancelByUser` command cancels an active dispatch. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | no | Active assignment ID at the time of cancellation, if any. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |
| `reason` | string | yes | User cancellation reason; do not include unmasked documents or other sensitive personal data. |
| `status` | enum | yes | `USER_CANCELLED`. |

### DispatchNoShowRecorded

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: post-sales, notification, reporting |
| **Trigger** | `RecordNoShow` ops command records that the rider did not appear after driver arrival. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `rideAssignmentId` | string | yes | Active assignment ID. |
| `riderAccountId` | string | yes | Account that owns the dispatch request. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup place/location reference. |
| `dropoffRef` | string | yes | Drop-off place/location reference. |
| `driverRef` | string | yes | Driver reference. |
| `vehicleRef` | string | yes | Vehicle reference. |
| `recordedAt` | RFC3339 UTC | yes | No-show recording timestamp. |
| `reason` | string | no | Operational reason or policy reference; do not include unmasked documents or other sensitive personal data. |
| `status` | enum | yes | `NO_SHOW`. |

### DispatchFailed

| Field | Description |
|---|---|
| **Producer** | dispatch |
| **Consumers** | deferred: post-sales, notification, reporting |
| **Trigger** | Timeout scan closes a `REQUESTED`/`MATCHING` request whose window elapsed (activation-wave increment 2026-07-09). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `rideRequestId` | string | yes | Ride request ID. |
| `riderAccountId` | string | yes | Rider account. |
| `travelerRef` | string | yes | Traveler reference. |
| `pickupRef` | string | yes | Pickup reference. |
| `dropoffRef` | string | yes | Dropoff reference. |
| `intentFingerprint` | string | yes | Mutual-exclusion fingerprint. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `reason` | string | yes | Failure reason (e.g. `MATCHING_TIMEOUT`). |
| `previousStatus` | enum | yes | Status before failure. |
| `status` | enum | yes | Always `FAILED`. |

## Accepted Commands

The command list follows the Dispatch domain command/event table. `RideEnded` is
the wire event name used for the domain `DispatchCompleted` completion fact.

| Command | Sender / Trigger | Produced event |
|---|---|---|
| `RequestDispatch` | API gateway / UI via `POST /api/v1/ride-requests` | `DispatchRequested` |
| `AssignDriver` | Internal ops simulation via `/assign` | `DriverAssigned` |
| `UpdateEta` | Internal ops simulation via `/eta` | `DriverEtaUpdated` |
| `MarkDriverArrived` | Internal ops simulation via `/driver-arrived` | `DriverArrived` |
| `StartRide` | Internal ops simulation via `/start` | `RideStarted` |
| `CompleteDispatch` | Internal ops simulation via `/complete` | `RideEnded` (`DispatchCompleted` domain fact) |
| `CancelByDriver` | Internal ops simulation via `/driver-cancel` | `DriverCancelled` |
| `CancelByUser` | API gateway / UI or ops via `/user-cancel` | `DispatchUserCancelled` |
| `RecordNoShow` | Internal ops simulation via `/no-show` | `DispatchNoShowRecorded` |

## Consumed upstream events

Dispatch registers no upstream event subscriptions in this activation wave.
External provider-platform events are deferred by the simulation-boundary ruling.

## Deferred downstream touchpoints

| Downstream context | Deferred events | Purpose when activated |
|---|---|---|
| Fulfillment | `DriverArrived`, `RideStarted`, `RideEnded` | Ride arrival/start/end facts for fulfillment evidence and lifecycle. |
| Notification | `DispatchRequested`, `DriverAssigned`, `DriverEtaUpdated`, `DriverArrived`, `DriverCancelled`, `DispatchUserCancelled`, `DispatchNoShowRecorded`, `RideEnded` | User-facing dispatch lifecycle notifications. |
| Post Sales | `DispatchUserCancelled`, `DispatchNoShowRecorded`, `RideEnded` | Cancellation, waiting-fee, no-show, and final-charge dispute handling. |
| Reporting | all Dispatch events | Dispatch lifecycle metrics and operational read models. |
