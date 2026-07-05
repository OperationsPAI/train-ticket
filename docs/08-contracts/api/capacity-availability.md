# Capacity & Availability — HTTP API

Last updated: 2026-07-05

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Capacity & Availability manages inventory for scheduled services: availability
snapshots, capacity holds, and capacity release. It is the authoritative
source for seat/unit availability.

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
| `snapshotVersion` | integer | Monotonically increasing version. |
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

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`

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
| `quantity` | integer | yes | Number of units to hold. |
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`) for correlation. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `holdId` | string | Hold identifier (`hold-<uuid>`). |
| `segmentRef` | string | Service segment. |
| `status` | enum | `HELD`, `CONFIRMED`, `RELEASED`, `EXPIRED` |
| `heldUntil` | timestamp | Hold expiry time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

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

**Response (200):** Full hold details.

**Error codes:** `NOT_FOUND`

## Open Issues

- None.
