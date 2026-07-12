# Fulfillment — HTTP API

Last updated: 2026-07-08

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

### Record Fulfillment Completion

**POST** `/api/v1/fulfillment-records/completions`

**Idempotency:** REQUIRED

Records arrival/provider/admin/system completion after a previously boarded segment has completed.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | string | yes | Reference to the entitlement (`ent-<uuid>`). |
| `segmentBookingId` | string | yes | Segment booking reference (`sb-<uuid>`). |
| `journeyOrderId` | string | yes | Order reference (`ord-<uuid>`). |
| `travelerId` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | string | yes | Segment reference (`seg-<uuid>`). |
| `completionSource` | string | yes | Completion source: `ARRIVAL`, `PROVIDER`, `ADMIN`, `SYSTEM`. |
| `completedAt` | timestamp | yes | When fulfillment completed; RFC3339 UTC. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `fulfillmentRecordId` | string | Fulfillment record ID (`fr-<uuid>`). |
| `status` | enum | `COMPLETED` |
| `completedAt` | timestamp | Completion timestamp accepted by the service. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

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

## POST `/api/v1/segment-status`

Operational declaration command for segment-level runtime status. This endpoint is intended for SYSTEM/OPS sources and is currently the only source of fulfillment `SegmentDelayed`, `SegmentArrived`, and `SegmentCancelled` events; fulfillment does not yet derive delay/arrival/cancellation facts internally.

Headers:

| Header | Required | Description |
|---|---|---|
| `Idempotency-Key` | yes | UUID-v7. Fulfillment folds this into command id `cmd-<Idempotency-Key>`; duplicate commands do not republish events. |
| `X-Correlation-Id` | no | Normalized to `corr-<uuid-v7>` when possible. |

Request body:

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | `SegmentRef` | yes | Affected segment. |
| `scheduledServiceRef` | string | yes | Scheduled service/run reference. |
| `serviceDate` | string | yes | Operating date, `YYYY-MM-DD`. |
| `status` | enum | yes | `DELAY`, `ARRIVAL`, or `CANCELLED`. |
| `estimatedArrivalAt` | RFC3339 UTC | conditional | Required for `DELAY`. |
| `arrivedAt` | RFC3339 UTC | conditional | Required for `ARRIVAL`. |
| `cancelledAt` | RFC3339 UTC | conditional | Required for `CANCELLED`. |
| `observedAt` | RFC3339 UTC | yes | Observation/declaration time. |
| `sourceSystem` | enum | yes | `SYSTEM` or `OPS`. |

Responses: `201 Created` with `segmentStatusRecordId`, `commandId`, `segmentRef`, `status`, `observedAt`; `400` for validation; `422` for domain-rule violations; `503` when outbox append is unavailable.
