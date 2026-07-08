# Capacity & Availability — Events & Commands

Last updated: 2026-07-04

## Published Events

### AvailabilitySnapshot

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | trip-planning, offer-management, fare-pricing |
| **Trigger** | Query snapshot computed on demand; not an authoritative hold. Published as a reference for offer quoting and availability hints. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `snapshotId` | string | yes | Stable snapshot identity. Format: `avs-<uuid>`. |
| `snapshotVersion` | u64 | yes | Monotonically increasing version of the snapshot. |
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
| `classRef` | string | yes | Seat class / cabin reference (e.g. `first`, `second`, `business`). |
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
- `AvailabilitySnapshot` is a read-model projection, not an event-sourced fact.
- Same `snapshotId` with higher `snapshotVersion` supersedes earlier versions.
- Consumers MUST discard snapshots where `validUntil` has elapsed.

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
| **Consumers** | booking-orchestration, waitlist |
| **Trigger** | `ReleaseHold` command processed (cancellation, refund, or compensation). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `holdId` | `HoldId` | yes | Hold ID. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit released. |
| `segmentRef` | string | yes | Service Plan segment the pool sells (added 2026-07-09 for waitlist matching; additive). |
| `interval` | `StationInterval` | yes | Station interval. |
| `releasedAt` | RFC3339 UTC | yes | Release timestamp. |
| `releaseReason` | string | yes | Reason (e.g. `post-sales-void`, `payment-expired`). |

### CapacityHoldExpired

| Field | Description |
|---|---|
| **Producer** | capacity-availability |
| **Consumers** | booking-orchestration |
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

## Accepted Commands

### HoldCapacity

| Field | Description |
|---|---|
| **Sender** | booking-orchestration |
| **Payload** | `holdId`, `inventoryPoolId`, `capacityUnitRef`, `interval`, `idempotencyKey`, `requestedAt`, `expiresAt`. |

### ConfirmHold

| Field | Description |
|---|---|
| **Sender** | booking-orchestration |
| **Payload** | `holdId`, `confirmedAt`. |

### ReleaseHold

| Field | Description |
|---|---|
| **Sender** | booking-orchestration, post-sales |
| **Payload** | `holdId`, `reason`. |
