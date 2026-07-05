# Fulfillment — HTTP API

Last updated: 2026-07-05

## Overview

Fulfillment records physical journey events: boarding verification, no-show
recording, and fulfillment status queries. These events are recorded after
the passenger travels.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

## Endpoints

### Verify Boarding

**POST** `/api/v1/fulfillment-records/boarding`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | string | yes | Reference to the entitlement (`ent-<uuid>`). |
| `segmentBookingId` | string | yes | Reference to the segment booking (`sb-<uuid>`). |
| `journeyOrderId` | string | yes | Reference to the journey order (`ord-<uuid>`). |
| `travelerId` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | string | yes | Segment reference (`seg-<uuid>`). |
| `source` | string | yes | Source: `GATE`, `STATION`, `PROVIDER`, `CONDUCTOR`, `ADMIN` |
| `sourceEventId` | string | yes | Source event identifier for idempotency. |
| `occurredAt` | timestamp | yes | When boarding physically occurred. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `fulfillmentRecordId` | string | Fulfillment record ID (`fr-<uuid>`). |
| `entitlementId` | string | Entitlement reference. |
| `status` | enum | `BOARDED` |
| `occurredAt` | timestamp | When boarding occurred. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`

### Record No-Show

**POST** `/api/v1/fulfillment-records/no-show`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | string | yes | Reference to the entitlement. |
| `segmentBookingId` | string | yes | Segment booking reference. |
| `journeyOrderId` | string | yes | Order reference. |
| `travelerId` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Segment reference. |
| `reason` | string | yes | `BOARDING_WINDOW_EXPIRED`, `VERIFICATION_FAILED`, `MANUAL_RECORD` |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `fulfillmentRecordId` | string | Fulfillment record ID. |
| `status` | enum | `NO_SHOW` |
| `assessedAt` | timestamp | When no-show was assessed. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`

### Get Fulfillment Record

**GET** `/api/v1/fulfillment-records/{fulfillmentRecordId}`

**Response (200):** Full record details.

**Error codes:** `NOT_FOUND`

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `RecordCheckIn` | station-device, provider-integration, admin-audit | Record a passenger check-in at station or gate. |
| `OpenEvidenceDispute` | system, admin-audit, customer-service | Open an evidence dispute for conflicting offline data. |
| `ResolveEvidenceDispute` | admin-audit, customer-service | Resolve an evidence dispute. |

## Open Issues

- None.
