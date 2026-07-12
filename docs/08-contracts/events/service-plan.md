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


### TemporaryServiceAdded

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning, notification |
| **Trigger** | A rush-period temporary service (`L*` train number) is added. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `tempServiceRef` | string | yes | Temporary scheduled service ID. |
| `baseServiceRef` | string | yes | Base scheduled service ID. |
| `period` | string | yes | Schedule period (`SPRING_RUSH`, `SUMMER_RUSH`, `NATIONAL_DAY`, `LABOR_DAY`). |

### TrainDelayed

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning, disruption-recovery, notification |
| **Trigger** | A delay is recorded for a service segment and propagated downstream. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `serviceRef` | string | yes | Scheduled service ID. |
| `segmentRef` | string | yes | Affected segment ID. |
| `delayMinutes` | integer | yes | Propagated delay minutes after dampening. |
| `estimatedNewDeparture` | RFC3339 UTC | yes | Estimated departure time after delay propagation. |

### TrainCancelled

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning, disruption-recovery, notification |
| **Trigger** | A service is cancelled for a specific service date. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `serviceRef` | string | yes | Scheduled service ID. |
| `date` | RFC3339 UTC | yes | Cancelled service date. |
| `reason` | string | yes | Operational cancellation reason code or text. |

### TrainRestored

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning, disruption-recovery, notification |
| **Trigger** | A previously cancelled service date is restored. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `serviceRef` | string | yes | Scheduled service ID. |
| `date` | RFC3339 UTC | yes | Restored service date. |

### TemporaryServiceExpired

| Field | Description |
|---|---|
| **Producer** | service-plan |
| **Consumers** | trip-planning |
| **Trigger** | Rush-period cleanup expires temporary services after period end. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `serviceRef` | string | yes | Temporary scheduled service ID. |
| `periodEndDate` | RFC3339 UTC | yes | End date of the rush period. |

## Consumer Notes

- Trip Planning treats these events as the authoritative plan feed: itinerary
  search must only return segments observed on this stream (no synthetic
  candidates once a plan exists).
- `originStopRef`/`destinationStopRef` may arrive as transport node refs
  (`tnd-*`); consumers that key by place must resolve node→place via the
  place-network registration events they have already consumed.

## Open Issues

- None.
