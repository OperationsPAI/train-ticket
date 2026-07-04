# Capacity & Availability — Events & Commands

Last updated: 2026-06-28

## Published Events

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
| **Consumers** | booking-orchestration |
| **Trigger** | `ReleaseHold` command processed (cancellation, refund, or compensation). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `holdId` | `HoldId` | yes | Hold ID. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit released. |
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
| **Trigger** | Hold could not be granted (conflict, unknown unit, idempotency conflict). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `requestedHoldId` | `HoldId` | yes | The hold ID that was requested. |
| `inventoryPoolId` | `InventoryPoolId` | yes | Pool ID. |
| `capacityUnitRef` | `CapacityUnitRef` | yes | Unit that caused the conflict. |
| `interval` | `StationInterval` | yes | Requested interval. |
| `idempotencyKey` | string | yes | Request idempotency key. |
| `reason` | enum | yes | `UNKNOWN_CAPACITY_UNIT`, `IDEMPOTENCY_CONFLICT`, or `OVERLAPPING_HOLD`. |
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
