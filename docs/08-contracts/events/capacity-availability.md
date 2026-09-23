# Capacity and Availability Events

Last updated: 2026-09-23.

## Availability read model

### AvailabilitySnapshot

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | trip-planning, offer-management, fare-pricing |
| **Source** | HTTP availability query, persisted as a snapshot; the PostgreSQL query does not append an `AvailabilitySnapshot` event to the outbox. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `snapshotId` | string | yes | Stable snapshot identity. Format: `avs-<uuid>`. |
| `snapshotVersion` | u64 | yes | Version 1 for each newly created snapshot identity. |
| `scheduledServiceRef` | string | yes | Reference to the scheduled service this snapshot covers (e.g. `ss-<uuid>`). |
| `segmentRef` | `SegmentRef` | yes | Service segment the snapshot applies to. |
| `capturedAt` | RFC3339 UTC | yes | When the snapshot was captured. |
| `validUntil` | RFC3339 UTC | yes | Snapshot expiry time; after this, the snapshot MUST NOT be used for quoting. |
| `sellable` | bool | yes | Whether at least one unit is sellable for the requested interval. |
| `remainingByClass` | `RemainingByClass[]` | yes | Per-class (seat-grade/cabin) remaining unit counts. |
| `totalUnits` | u32 | yes | Total sellable units in the pool. |
| `availableCount` | u32 | yes | Estimated number of available units. |
| `status` | enum | yes | Canonical availability status (see mapping table below). |

**RemainingByClass:**

| Field | Type | Required | Description |
|---|---|---|---|
| `classRef` | string | yes | Purchased class, such as `FIRST_CLASS`, `SECOND_CLASS`, `BUSINESS_CLASS`, `SLEEPER_HARD`, `SLEEPER_SOFT` or `STANDING`. |
| `total` | u32 | yes | Total units in this class. |
| `available` | u32 | yes | Estimated available units in this class. |

**Status vocabulary and cross-context mapping:**

| Canonical Status | trip-planning hint | offer-management confidence | Description |
|---|---|---|---|
| `AVAILABLE` | `available_hint` | `confirmed-snapshot` | Sufficient sellable units; quote confidently. |
| `LIMITED` | `limited` | `low` | Fewer than threshold units remain; may sell out soon. |
| `UNKNOWN` | `unknown` | `estimated` | Snapshot data stale or incomplete; estimate only. |
| `UNAVAILABLE` | `unavailable` | N/A | No sellable units; cannot quote. |

**Idempotency/Ordering Notes:**

`AvailabilitySnapshot` is an observation and does not reserve capacity.
Each query creates its own snapshot identity.
Consumers must discard snapshots where `validUntil` has elapsed.

## Published events

### CapacityHeld

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | booking-orchestration |
| **Trigger** | `HoldCapacity` command processed; capacity unit reserved for a station interval. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `holdId` | `HoldId` | yes | Canonical hold ID (`hold-<uuid>`). |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool that granted the hold. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | The specific unit held. |
| `interval` | `StationInterval` | yes | Station interval for which the hold applies. |
| `idempotencyKey` | string | yes | Idempotency key from the request. |
| `expiresAt` | RFC3339 UTC | yes | Hold expiry time. |
| `idempotentReplay` | bool | yes | True if this event is a replay of an identical previous request. |

Booking Orchestration correlates the Hold through its reservation idempotency key.
`capacityUnitRef` is an inventory reference and has no passenger seat number guarantee.
In the standing supply integration it identifies `quota:<policyVersion>:<index>`.
The [standing policy](../standing-capacity-policy.md) defines quota evidence and deployment status.

### CapacityHoldConfirmed

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | booking-orchestration |
| **Trigger** | `ConfirmHold` command processed (payment conditions met). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `holdId` | `HoldId` | yes | Hold ID. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit held. |
| `interval` | `StationInterval` | yes | Station interval. |
| `confirmedAt` | RFC3339 UTC | yes | Confirmation timestamp. |

### CapacityReleased

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | booking-orchestration, waitlist, seat-assignment |
| **Trigger** | `ReleaseHold` command processed (cancellation, refund, or compensation). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `holdId` | `HoldId` | yes | Hold ID. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit released. |
| `segmentRef` | string | yes | Service Plan segment sold by the inventory pool. |
| `interval` | `StationInterval` | yes | Station interval. |
| `releasedAt` | RFC3339 UTC | yes | Release timestamp. |
| `releaseReason` | string | yes | Reason (e.g. `post-sales-void`, `payment-expired`). |
| `references` | object | yes | `sourceContext`, `orderRef`, `segmentBookingRef` and `travelerRef` recorded on the Hold. |

Seat Assignment uses `holdId` to recover the associated allocations.
This event currently lacks authoritative date and class fields for automatic Waitlist matching.
Publication alone does not establish successful Waitlist fulfillment.

### CapacityHoldExpired

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | booking-orchestration, seat-assignment |
| **Trigger** | Hold TTL elapsed without confirmation. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `holdId` | `HoldId` | yes | Hold ID. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit expired. |
| `interval` | `StationInterval` | yes | Station interval. |
| `expiredAt` | RFC3339 UTC | yes | Expiry timestamp. |

### CapacityHoldFailed

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | booking-orchestration |
| **Trigger** | Hold could not be granted (conflict, unknown unit, idempotency conflict, or pool sold out). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `requestedHoldId` | `HoldId` | yes | The hold ID that was requested. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit that caused the conflict; the sentinel `NONE` when reason is `NO_AVAILABLE_CAPACITY` (no specific unit is implicated). |
| `interval` | `StationInterval` | yes | Requested interval. |
| `idempotencyKey` | string | yes | Request idempotency key. |
| `reason` | enum | yes | `UNKNOWN_CAPACITY_UNIT`, `IDEMPOTENCY_CONFLICT`, `OVERLAPPING_HOLD`, or `NO_AVAILABLE_CAPACITY`. |
| `conflictingHoldId` | `HoldId` | no | Conflicting hold ID if reason is `OVERLAPPING_HOLD`. |

### CapacitySnapshotUpdated

The domain produces this event after inventory lifecycle changes.
The PostgreSQL adapter serializes the following fields:

| Field | Description |
|---|---|
| `segmentRef`, `departureDate` | Inventory pool segment and service date. |
| `totalCapacity`, `remainingCapacity` | Admissible capacity and the remaining count calculated by the pool. |
| `physicalCapacity` | Concrete seat or berth count; zero for standing quota pools. |
| `holdCount`, `confirmedCount` | Pool occupancy by lifecycle state. |
| `utilizationPct`, `snapshotVersion` | Utilization and source snapshot version. |
| `classes` | Per class `classRef`, `physicalCapacity`, `effectiveCapacity`, `holdCount`, `confirmedCount`, `remainingCapacity` and `overbookingPolicy`. |

`overbookingPolicy` contains `maxOverbookingPct`, `noShowRate` and `safetyMarginPct`.
Standing quota confirmation does not produce a physical seat overbooking event.
The supply integration combines allocations across pools during admission.
Snapshot events still use the individual pool's counts, so cross pool snapshot consistency remains a production activation requirement.

### Other domain events

| Event | Payload |
|---|---|
| `OverbookingThresholdReached` | `poolId`, `physicalCapacity`, `confirmedCount`, `overbookingPct`. |
| `WaitlistActivated` | `segmentRef`, `departureDate`, `queuePosition`. |
| `WaitlistCapacityFreed` | `segmentRef`, `departureDate`, `freedSlots`. |

These event serializers exist in the PostgreSQL adapter.
Their presence does not establish that downstream Waitlist automation has a complete class, date and payment guarantee.

## Consumed events

| Event | Capacity action |
|---|---|
| `SegmentReservationRequested` | Resolve accepted booking supply and request one Hold for the traveler. |
| `SegmentReservationConfirmed` | Confirm the matching Hold or persist confirmation until its Hold arrives. |
| `SegmentTicketed` | Confirm the matching Hold using the ticketed booking reference. |
| `SegmentBookingCancelled` | Release the matching Hold or persist cancellation until its Hold arrives. |
| `PostSalesApplied` | Process the applicable release for the recorded booking. |
| `EntitlementVoided` | Release the Hold associated with the voided entitlement. |

`SegmentReservationRequested` carries `segmentBookingId`, `journeyOrderId`, `segmentRef`, `travelerRef` and `idempotencyKey`.
The coordinated Booking implementation also persists the accepted class, scheduled service and service date.
The supply integration validates event references against those booking facts and persists them in `reservation_supply_facts`.
See [integration requirements](../../10-domain-enrichment/capacity-overbooking.md) before activating the coordinated changes.
