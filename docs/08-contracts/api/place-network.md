# Place & Network — HTTP API

Last updated: 2026-07-05

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Place & Network manages geographic places (cities, stations, airports) and
transport nodes (platforms, gates). It serves as the reference master data
for all location lookups.

## Endpoints

### Create a Place

Registers a new geographic place.

**POST** `/api/v1/places`

**Idempotency:** REQUIRED (`Idempotency-Key`)

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `placeType` | enum | yes | `CITY`, `STATION`, `AIRPORT`, `PORT` |
| `canonicalName` | string | yes | Display name of the place. |
| `code` | string | no | Short code (e.g. IATA/station code). |
| `timezone` | string | no | IANA timezone identifier. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `placeId` | string | Canonical place ID (`plc-<uuid>`). |
| `placeType` | enum | As requested. |
| `canonicalName` | string | Display name. |
| `status` | enum | `ACTIVE`, `DRAFT`, `DEPRECATED`, `RETIRED` |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`

### Get a Place

**GET** `/api/v1/places/{placeId}`

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `placeId` | string | Canonical place ID. |
| `placeType` | enum | Type of place. |
| `canonicalName` | string | Display name. |
| `code` | string | Short code, if set. |
| `timezone` | string | IANA timezone, if set. |
| `status` | enum | Current status. |
| `nodes` | array | List of associated transport nodes. |

**Error codes:** `NOT_FOUND`

### List Places

**GET** `/api/v1/places?limit=20&offset=0&status=ACTIVE`

**Query parameters:** `limit`, `offset`, `status` (optional filter)

**Response (200):** Paginated response with `items`, `total`, `limit`, `offset`.

### Create a Transport Node

**POST** `/api/v1/transport-nodes`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `placeId` | string | yes | Parent place ID (`plc-<uuid>`). |
| `displayName` | string | yes | Display name (e.g. "Platform 3"). |
| `servingModes` | string[] | yes | Transport modes served (e.g. `["RAIL"]`). |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `nodeId` | string | Canonical node ID (`tnd-<uuid>`). |
| `placeId` | string | Parent place. |
| `displayName` | string | Display name. |
| `servingModes` | string[] | Transport modes. |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND` (placeId), `CONFLICT`

### Get a Transport Node

**GET** `/api/v1/transport-nodes/{nodeId}`

**Response (200):** Node details as above.

**Error codes:** `NOT_FOUND`

## Open Issues

- None.
