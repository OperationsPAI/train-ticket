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
| `nodes` | array | List of associated transport nodes using a summary shape (`nodeId`, `displayName`, `servingModes`, and optional access-time weights when configured). |

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
| `accessTimeMinutes` | integer | no | Non-negative walking/wayfinding access-time weight in whole minutes for entering/leaving this node. Omitted when unknown. |
| `walkingEdges` | array | no | Optional directed walking/access edges from this node to adjacent transport nodes. Each item is `{toNodeId, walkingTimeMinutes}` where `toNodeId` is a TransportNode ID (`tnd-<uuid>` or configured node ref) and `walkingTimeMinutes` is an optional non-negative integer in whole minutes. Omit `walkingTimeMinutes` when the edge exists but its weight is unknown; explicit `0` is a valid zero-minute weight. Omit `walkingEdges` or use an empty array when no edges are configured. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `nodeId` | string | Canonical node ID (`tnd-<uuid>`). |
| `placeId` | string | Parent place. |
| `displayName` | string | Display name. |
| `servingModes` | string[] | Transport modes. |
| `accessTimeMinutes` | integer | Optional; same semantics and minute units as request. Present only when configured. |
| `walkingEdges` | array | Optional; same `{toNodeId, walkingTimeMinutes}` directed edge shape as request. Present only when one or more walking/access edges are configured; per-edge `walkingTimeMinutes` is omitted when unknown and present as `0` when explicitly configured as zero. |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND` (placeId), `CONFLICT`

### Get a Transport Node

**GET** `/api/v1/transport-nodes/{nodeId}`

**Response (200):** Node details as above.

**Error codes:** `NOT_FOUND`

## Open Issues

- None.
