# Place & Network — Events & Commands

Last updated: 2026-06-28

## Published Events

### PlaceRegistered

| Field | Description |
|---|---|
| **Producer** | place-network |
| **Consumers** | service-plan, trip-planning |
| **Trigger** | New place created or activated. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `placeId` | `PlaceId` | yes | Canonical place ID (`plc-<uuid>`). |
| `placeType` | enum | yes | `CITY`, `STATION`, `AIRPORT`, `PORT`, etc. |
| `canonicalName` | string | yes | Display name. |
| `status` | enum | yes | `ACTIVE`, `DRAFT`, `DEPRECATED`, `RETIRED`. |

### TransportNodeRegistered

| Field | Description |
|---|---|
| **Producer** | place-network |
| **Consumers** | service-plan |
| **Trigger** | New transport node created. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `nodeId` | `TransportNodeId` | yes | Canonical node ID (`tnd-<uuid>`). |
| `placeId` | `PlaceId` | yes | Parent place. |
| `displayName` | string | yes | Display name. |
| `servingModes` | string[] | yes | Transport modes served. |
| `accessTimeMinutes` | integer | no | Non-negative walking/wayfinding access-time weight in whole minutes for entering/leaving this node. Omitted when unknown. |
| `walkingEdges` | array | no | Optional directed walking/access edges from this node to adjacent transport nodes. Each item is `{toNodeId, walkingTimeMinutes}` where `toNodeId` is a TransportNode ID (`tnd-<uuid>` or configured node ref) and `walkingTimeMinutes` is an optional non-negative integer in whole minutes. Omit `walkingTimeMinutes` when the edge exists but its weight is unknown; explicit `0` is a valid zero-minute weight. Omit `walkingEdges` or use an empty array when no edges are configured. |
