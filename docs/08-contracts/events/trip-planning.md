# Trip Planning — Events & Commands

Last updated: 2026-06-28

## Published Events

### ItineraryProposed

| Field | Description |
|---|---|
| **Producer** | trip-planning |
| **Consumers** | offer-management |
| **Trigger** | `SearchItineraries` command received with valid `TripIntent`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `intentRef` | string | yes | Hash/fingerprint of the original `TripIntent`. |
| `itineraries` | `Itinerary[]` | yes | Ordered list of candidate itineraries. |
| `planningSnapshotRefs` | string[] | yes | References to upstream snapshots used during planning. |

**Itinerary:**

| Field | Type | Required | Description |
|---|---|---|---|
| `itineraryRef` | string | yes | Stable itinerary reference (e.g. `itin_<sha256>`). |
| `legs` | `LegCandidate[]` | yes | Ordered legs comprising the itinerary. |
| `priceHint` | `PriceHint` | no | Non-authoritative price estimate. |
| `availabilityHint` | `AvailabilityHint` | no | Non-authoritative availability estimate. |

**LegCandidate:**

| Field | Type | Required | Description |
|---|---|---|---|
| `servicePlanRef` | string | yes | Reference to the service plan. |
| `serviceSegmentRef` | string | yes | Reference to the service segment. |
| `originStopRef` | string | yes | Origin stop reference. |
| `destinationStopRef` | string | yes | Destination stop reference. |
| `departureTime` | RFC3339 UTC | yes | Scheduled departure. |
| `arrivalTime` | RFC3339 UTC | yes | Scheduled arrival. |
| `mode` | string | no | Transport mode (default: `train`). |

**Idempotency/Ordering Notes:**
- Same `intentRef` may produce different results if upstream snapshots change.
- Consumers should not cache results as authoritative.

## Accepted Commands

### SearchItineraries

| Field | Description |
|---|---|
| **Sender** | API gateway / UI |
| **Payload** | `TripIntent` |

**TripIntent:**

| Field | Type | Required | Description |
|---|---|---|---|
| `originRef` | `PlaceId` or `TransportNodeId` | yes | Origin place/node. |
| `destinationRef` | `PlaceId` or `TransportNodeId` | yes | Destination place/node. |
| `departureWindowStart` | RFC3339 UTC | yes | Earliest departure time. |
| `departureWindowEnd` | RFC3339 UTC | yes | Latest departure time. |
| `passengerCount` | u32 | yes | Number of passengers. |
| `preferences` | `PreferenceConstraints` | no | Optional constraints. |

**PreferenceConstraints:**

| Field | Type | Required | Description |
|---|---|---|---|
| `allowedModes` | string[] | no | Allowed transport modes (e.g. `["train"]`). |
| `maxConnections` | u32 | no | Maximum number of connections. |
| `minConnectionMinutes` | u32 | no | Minimum connection time in minutes. |
| `maxConnectionWaitMinutes` | u32 | no | Maximum connection wait. |
| `maxPriceMinor` | i64 | no | Maximum total price in minor units. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| place-network | `PlaceId`, `TransportNodeId` | Origin/destination resolution. |
| service-plan | `ServiceSegment`, `ScheduledService` | Leg candidate generation. |
| capacity-availability | `AvailabilitySnapshot` | Availability hints. |
| fare-pricing | `FareQuote` | Price hints. |
