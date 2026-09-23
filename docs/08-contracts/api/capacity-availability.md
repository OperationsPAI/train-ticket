# Capacity and Availability HTTP API

Last updated: 2026-09-23

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs, timestamps, and Money.

Capacity and Availability manages inventory for scheduled services, availability snapshots, capacity holds, and capacity release.
It owns capacity admission and Hold lifecycle.
Seat Assignment owns physical seat allocation.

The physical supply integration uses Service Plan segment facts and Seat Assignment's internal capacity facts.
It is under coordinated integration with Booking and loadgen; verify deployed images before treating it as active.
See [integration requirements](../../10-domain-enrichment/capacity-overbooking.md).
Standing quota follows [standing-second-class-10pct-v1](../standing-capacity-policy.md) and is separate from concrete seat capacity.

## Endpoints

### Query Availability Snapshot

Returns a non-authoritative availability snapshot for a service segment.

**GET** `/api/v1/availability-snapshots?scheduledServiceRef={ssRef}&segmentRef={segRef}`

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `segmentRef` | string | yes | Service segment reference. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `snapshotId` | string | Stable snapshot identity (`avs-<uuid>`). |
| `snapshotVersion` | integer | Version of this snapshot identity; an on demand query creates a new identity with version 1. |
| `scheduledServiceRef` | string | Scheduled service reference. |
| `segmentRef` | string | Segment reference. |
| `capturedAt` | timestamp | When snapshot was captured. |
| `validUntil` | timestamp | Snapshot expiry. |
| `sellable` | boolean | Whether at least one unit is sellable. |
| `remainingByClass` | array | Per-class remaining counts. |
| `totalUnits` | integer | Total sellable units. |
| `availableCount` | integer | Estimated available units. |
| `status` | enum | `AVAILABLE`, `LIMITED`, `UNKNOWN`, `UNAVAILABLE` |

**RemainingByClass item:**

| Field | Type | Description |
|---|---|---|
| `classRef` | string | Seat class reference. |
| `total` | integer | Total units in this class. |
| `available` | integer | Estimated available units. |

**Error codes:** `VALIDATION_FAILED`, `PRECONDITION_FAILED`, `UNAVAILABLE`

### Hold Capacity

Places a hold on capacity for a segment.

**POST** `/api/v1/capacity-holds`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | string | yes | Service segment reference. |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `classRef` | string | yes | Seat class reference. |
| `quantity` | integer | yes | Exactly one capacity unit for the referenced single traveler reservation. |
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`) for correlation. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `holdId` | string | Hold identifier (`hold-<uuid>`). |
| `segmentRef` | string | Service segment. |
| `status` | enum | `HELD`, `CONFIRMED`, `RELEASED`, `EXPIRED`, `FAILED` |
| `heldUntil` | timestamp | Hold expiry time. |

**Error codes:** `VALIDATION_FAILED`, `PRECONDITION_FAILED`, `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

### Confirm a Hold

**POST** `/api/v1/capacity-holds/{holdId}/confirm`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `holdId` | string | Hold identifier. |
| `status` | enum | `CONFIRMED` |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`

### Release a Hold

**POST** `/api/v1/capacity-holds/{holdId}/release`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `holdId` | string | Hold identifier. |
| `status` | enum | `RELEASED` |

**Error codes:** `NOT_FOUND`, `CONFLICT`

### Get Capacity Hold

**GET** `/api/v1/capacity-holds/{holdId}`

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `holdId` | string | Hold identifier. |
| `segmentRef` | string | Service segment. |
| `status` | enum | `REQUESTED`, `HELD`, `CONFIRMED`, `RELEASED`, `EXPIRED`, `FAILED`. |
| `heldUntil` | timestamp | Hold expiry time. |
| `requestedAt` | timestamp | When the hold was requested. |
| `segmentBookingRef` | string? | Booking associated with the held capacity. |
| `travelerRef` | string? | Traveler reference, when the hold carries one. |
| `capacityUnitRef` | string | Capacity unit or standing admission slot associated with the Hold. |
| `interval` | `StationInterval` | `{fromSeq, toSeq}` the hold covers. |

`CapacityReleased` and `CapacityHoldExpired` identify the complete Hold through `holdId`.
Seat Assignment recovers allocations associated with that Hold.
`capacityUnitRef` and `interval` retain the inventory evidence carried by the release event.

`capacityUnitRef` identifies the held capacity unit or quota slot.
It is distinct from the purchased class and from the passenger facing seat number.

**Error codes:** `NOT_FOUND`

## Integration status

Physical supply and standing quota admission are under coordinated integration.
The new code resolves class, service and date from accepted booking facts, validates published supply, and counts pending holds with active allocations.
Before production activation, verify complete historical occupancy, interval mapping, dependency failure behavior and compensation for failed replacement reservations.
See [integration requirements](../../10-domain-enrichment/capacity-overbooking.md) for verification coverage and current deployment boundaries.
