# Service Plan — HTTP API

Last updated: 2026-07-05

## Overview

Service Plan manages scheduled services (train runs), service segments (route
sections), and the association between routes and services. It provides the
master schedule data used by Trip Planning and Capacity.

## Endpoints

### Create a Scheduled Service

**POST** `/api/v1/scheduled-services`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `serviceRef` | string | no | External service reference (optional; generated if absent). |
| `carrierId` | string | yes | Carrier operating the service (`car-<uuid>`). |
| `serviceNumber` | string | yes | Public service number (e.g. "G1234"). |
| `departureTime` | timestamp | yes | Scheduled departure at origin. |
| `arrivalTime` | timestamp | yes | Scheduled arrival at destination. |
| `originNodeId` | string | yes | Origin transport node ID. |
| `destinationNodeId` | string | yes | Destination transport node ID. |
| `status` | enum | no | `ACTIVE`, `SUSPENDED`, `CANCELLED` (default `ACTIVE`). |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `scheduledServiceRef` | string | Reference (`ss-<uuid>`). |
| `serviceNumber` | string | Public service number. |
| `status` | enum | Current status. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `NOT_FOUND`

### Get Scheduled Service

**GET** `/api/v1/scheduled-services/{serviceRef}`

**Response (200):** Full service details.

**Error codes:** `NOT_FOUND`

### List Scheduled Services

**GET** `/api/v1/scheduled-services?limit=20&offset=0&carrierId=...`

**Response (200):** Paginated response.

### Create a Service Segment

**POST** `/api/v1/service-segments`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `scheduledServiceRef` | string | yes | Parent scheduled service. |
| `originStopRef` | string | yes | Origin stop node ID. |
| `destinationStopRef` | string | yes | Destination stop node ID. |
| `departureTime` | timestamp | yes | Segment departure time. |
| `arrivalTime` | timestamp | yes | Segment arrival time. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `segmentRef` | string | Segment reference (`seg-<uuid>`). |
| `scheduledServiceRef` | string | Parent service. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`

## Open Issues

- None.
