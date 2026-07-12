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
