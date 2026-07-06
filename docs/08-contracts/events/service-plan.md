# Service Plan Event Contracts

Service Plan owns scheduled services (运行计划) and their sellable service
segments. It publishes plan facts to `events:service-plan`; Trip Planning
consumes them to build itinerary candidates (see messaging.md subscription
table row 2).

Field shapes reference docs/08-contracts/shared-primitives.md for IDs and
timestamps. All timestamps are RFC3339 UTC.

## Published Events

### ServicePlanPublished

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning |
| **Trigger** | `CreateScheduledService` command processed — a scheduled service becomes part of the sellable plan. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `scheduledServiceRef` | string | yes | Canonical scheduled service ID (`ss-<uuid>`). |
| `serviceNumber` | string | yes | Public service number (e.g. `G1234`). |
| `status` | string | yes | Plan status of the scheduled service. |
| `carrierId` | string | yes | Operating carrier reference. |
| `departureTime` | RFC3339 UTC | yes | Scheduled departure of the full service. |
| `arrivalTime` | RFC3339 UTC | yes | Scheduled arrival of the full service. |
| `originNodeId` | string | yes | Origin transport node (`tnd-<uuid>`, place-network). |
| `destinationNodeId` | string | yes | Destination transport node (`tnd-<uuid>`, place-network). |

### ServicePlanChanged

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning |
| **Trigger** | `CreateServiceSegment` command processed — a sellable segment is added to (or changed within) an already-published scheduled service. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | string | yes | Canonical service segment ID (`seg-<uuid>`). |
| `scheduledServiceRef` | string | yes | Parent scheduled service ID (`ss-<uuid>`). |
| `originStopRef` | string | yes | Segment origin stop (transport node ref). |
| `destinationStopRef` | string | yes | Segment destination stop (transport node ref). |
| `departureTime` | RFC3339 UTC | yes | Segment departure time. |
| `arrivalTime` | RFC3339 UTC | yes | Segment arrival time. |

## Consumer Notes

- Trip Planning treats these events as the authoritative plan feed: itinerary
  search must only return segments observed on this stream (no synthetic
  candidates once a plan exists).
- `originStopRef`/`destinationStopRef` may arrive as transport node refs
  (`tnd-*`); consumers that key by place must resolve node→place via the
  place-network registration events they have already consumed.

## Open Issues

- None.
