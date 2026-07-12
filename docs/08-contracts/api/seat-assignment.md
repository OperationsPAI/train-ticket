# Seat Assignment — HTTP API

Last updated: 2026-07-09

## Overview

Seat Assignment owns versioned seat/berth maps and the concrete assignment of a
traveler to a `SeatUnit` or to the successful `STANDING` no-seat semantic. It does
not own Capacity & Availability counts, holds, payment, booking saga state, or
Entitlement credential issuance.

Activation-wave rulings:

- **RULING (ADR-0003 wave A): SeatMap is operations CRUD.** Operators create and
  maintain SeatMaps by submitting deterministic SIM composition seed data. Seat
  Assignment does not consume a real railway seat-map network and does not infer
  SeatMaps from legacy random seat assignment.
- **RULING (ADR-0003 wave A): allocation is called from Entitlement & Ticketing.**
  Entitlement & Ticketing performs an outbound internal HTTP call to Seat
  Assignment in the ticket-issuance path before it commits the credential. The
  same wave must implement the Entitlement contract additions documented in
  `docs/08-contracts/api/entitlement-ticketing.md`: issue requests may carry
  `seatPreferences`, and issued credentials carry `seatRef`.
- `STANDING` is a successful allocation result. It never creates a virtual
  `SeatUnit`, does not consume adjacency capacity, and must not be displayed as
  "seat pending".
- Adjacent seats/berths are best effort. Seat Assignment may downgrade adjacency
  while still returning a successful allocation, but it must publish the downgrade
  fact in `AdjacentAllocationSolved` / `AdjacencyDegradationAccepted`.
- Capacity release remains owned by Capacity & Availability. Seat Assignment
  consumes `CapacityReleased` and `CapacityHoldExpired` events to recover concrete
  seat assignments for the matching `holdId`; it does not replace `ReleaseHold`.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, and pagination conventions. All JSON fields are
camelCase, enum values are SCREAMING_SNAKE_CASE, and timestamps are RFC3339 UTC.
Money is not used in this contract.

## Identity, idempotency, and tracing

State-changing HTTP endpoints require an `Idempotency-Key` header whose value is a
UUID-v7-shaped string. API-triggered commands use the header value directly as the
command key and fold it into a command id `cmd-<uuid-v7>` for events and outbound
trace wiring. `X-Correlation-Id`, when supplied, is folded/validated as
`corr-<uuid-v7>`; otherwise Seat Assignment generates a new `corr-<uuid-v7>`.

Internal event-driven and service-originated commands do not put domain material
on the wire. The application canonicalizes the material listed below, hashes it
with SHA-256, stamps UUID version 7 and RFC-4122 variant bits, persists the
resulting UUID-v7-shaped key, and reuses it on retry.

| Command source | Wire key behavior | Material folded when Seat Assignment originates or consumes internally |
|---|---|---|
| Operator `POST /api/v1/seat-maps` | Use `Idempotency-Key` header directly. | Not applicable. |
| Operator publish/retire/seat-unit mutation | Use `Idempotency-Key` header directly. | Not applicable. |
| Entitlement internal allocation call | Entitlement sends a persisted UUID-v7 `Idempotency-Key`; Seat Assignment uses it directly. | Entitlement must persist a key folded from `segmentBookingId`, `travelerRef`, `capacityHoldId`, normalized `seatPreferences`, and `issuePurpose`. |
| Consumed `EntitlementIssued` confirmation | No HTTP key. | `seatAllocationId`, `entitlementId`, consumed envelope `eventId`. |
| Consumed `EntitlementVoided` / `EntitlementIssueFailed` release | No HTTP key. | `seatAllocationId` or `segmentBookingId`, reason, consumed envelope `eventId`. |
| Consumed `CapacityReleased` recovery | No HTTP key. | `holdId`, `capacityUnitRef`, normalized `interval`, consumed envelope `eventId`. |
| Consumed `CapacityHoldExpired` recovery | No HTTP key. | `holdId`, `capacityUnitRef`, normalized `interval`, consumed envelope `eventId`. |

Replays with the same key and same canonical body return the original response.
Reusing the same key with different material returns `IDEMPOTENCY_KEY_REUSED`
(422). Terminal allocations (`CONFIRMED`, `RELEASED`, `EXPIRED`, `FAILED`,
`MISSED`) remain observable via GET; terminal GETs are restful and do not
resurrect state.

## Enums

### SeatMap status

| Status | Meaning | Terminal |
|---|---|---|
| `DRAFT` | SeatMap was built from SIM seed data and is not assignable. | no |
| `VALIDATING` | SeatUnit, coach, class, and interval compatibility checks are running. | no |
| `PUBLISHED` | This version is authoritative for new allocations. | no |
| `SUPERSEDED` | A later version is authoritative; this version remains for historical interpretation. | no |
| `RETIRED` | Service ended or map was withdrawn; query only. | yes |
| `FAILED` | Build or validation failed; not assignable. | yes |

### SeatAllocation status

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `REQUESTED` | Allocation command accepted and being evaluated. | `ALLOCATED`, `STANDING`, `FAILED`, `MISSED` |
| `ALLOCATED` | A concrete SeatUnit/berth is temporarily assigned. | `CONFIRMED`, `RELEASED`, `EXPIRED`, `ALLOCATED`, `MISSED` |
| `STANDING` | No concrete SeatUnit; successful no-seat allocation. | `CONFIRMED`, `RELEASED`, `EXPIRED`, `MISSED` |
| `CONFIRMED` | Entitlement was issued and the credential displays this seat fact. | `RELEASED` |
| `RELEASED` | Assignment was returned to the seat pool. | Terminal |
| `EXPIRED` | Capacity hold expired before confirmation. | Terminal |
| `FAILED` | Allocation failed and cannot be retried in-place. | Terminal |
| `MISSED` | Late or saga-already-closed fact; cannot be retried in-place. | Terminal |

### Allocation and preference enums

| Enum | Values |
|---|---|
| `allocationType` | `SEAT`, `BERTH`, `STANDING` |
| `berthPosition` | `UPPER`, `MIDDLE`, `LOWER`, `SIDE_UPPER`, `SIDE_LOWER` |
| `seatPosition` | `WINDOW`, `AISLE`, `MIDDLE`, `LOWER_DECK`, `UPPER_DECK` |
| `adjacencyPreference` | `NONE`, `SAME_COACH`, `SAME_ROW`, `ADJACENT`, `SAME_COMPARTMENT` |
| `degradationReason` | `NONE`, `NO_ADJACENT_BLOCK`, `CLASS_MISMATCH`, `INTERVAL_CONFLICT`, `BERTH_PREFERENCE_UNAVAILABLE`, `STANDING_ASSIGNED`, `POLICY_LIMIT` |
| `releaseReason` | `VOIDED`, `CHANGED`, `ISSUE_FAILED`, `CAPACITY_RELEASED`, `HOLD_EXPIRED`, `DISRUPTION`, `MANUAL_CORRECTION` |
| `failureReason` | `NO_COMPATIBLE_SEAT`, `OVERLAPPING_ALLOCATION`, `CAPACITY_REFERENCE_MISSING`, `PREFERENCE_UNSATISFIABLE`, `SEAT_MAP_VERSION_STALE`, `IDEMPOTENCY_CONFLICT` |

## Resource representations

### SeatMap

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | Canonical SeatMap ID (`smap-<uuid>`). |
| `scheduledServiceRef` | string | yes | Scheduled service reference (`ss-<uuid>`). |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD` local service calendar form. |
| `compositionVersion` | string | yes | Operator/SIM composition version. Published versions are immutable. |
| `seatMapVersion` | integer | yes | Monotonic version for this SeatMap. |
| `source` | enum | yes | `SIM_SEED` in this wave. |
| `compositionSeed` | string | yes | Deterministic seed used by the SIM gateway. Same seed + version + mapping version produces the same topology. |
| `mappingVersion` | string | yes | SIM-to-core mapping version. |
| `status` | enum | yes | SeatMap status. |
| `coaches` | array[Coach] | yes | Coach topology. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `publishedAt` | RFC3339 UTC | no | Publish timestamp for assignable versions. |
| `retiredAt` | RFC3339 UTC | no | Retirement timestamp for terminal maps. |

### Coach

| Field | Type | Required | Description |
|---|---|---|---|
| `coachRef` | string | yes | Stable coach reference within the SeatMap (`coach-<uuid>` or deterministic SIM code mapped by ACL). |
| `coachNo` | string | yes | Passenger-facing coach number. |
| `classRef` | string | yes | Seat class/cabin reference that must match the capacity class. |
| `coachType` | enum | yes | `SEAT`, `BERTH`, `MIXED`, `STANDING_ONLY`. |
| `assignable` | boolean | yes | Whether this coach may receive new concrete allocations. |
| `seatUnits` | array[SeatUnit] | yes | Concrete units. Empty for `STANDING_ONLY`. |

### SeatUnit

| Field | Type | Required | Description |
|---|---|---|---|
| `seatUnitRef` | string | yes | Stable seat/berth reference (`su-<uuid>`). |
| `coachRef` | string | yes | Parent coach reference. |
| `coachNo` | string | yes | Passenger-facing coach number. |
| `seatNo` | string | yes | Passenger-facing seat/berth number inside the coach. |
| `allocationType` | enum | yes | `SEAT` or `BERTH`; never `STANDING`. |
| `berthPosition` | enum | no | Required when `allocationType=BERTH`. |
| `seatPosition` | enum | no | Seat position hint when known. |
| `rowNo` | string | no | Row or compartment number for adjacency solving. |
| `adjacencyGroupKey` | string | no | Deterministic key for seats/berths considered adjacent. |
| `assignable` | boolean | yes | False when maintenance/reserved/fault marks block allocation. |
| `unavailableReason` | string | no | Operator reason; do not include unmasked personal data. |

### SeatPreferences

`SeatPreferences` is shared with Entitlement & Ticketing issue requests in this
wave. Entitlement must pass the same shape when calling Seat Assignment.

| Field | Type | Required | Description |
|---|---|---|---|
| `acceptStanding` | boolean | yes | Whether a `STANDING` allocation is acceptable when no concrete compatible SeatUnit can be assigned. |
| `adjacencyPreference` | enum | no | Desired grouping: `NONE`, `SAME_COACH`, `SAME_ROW`, `ADJACENT`, `SAME_COMPARTMENT`. Default `NONE`. |
| `adjacencyGroupRef` | string | no | Client/order-level grouping reference for fellow travelers. |
| `preferredSeatPositions` | array[enum] | no | Ordered seat preferences using `seatPosition` values. |
| `preferredBerthPositions` | array[enum] | no | Ordered berth preferences using `berthPosition` values. |
| `sameCompartment` | boolean | no | Stronger berth adjacency hint; best effort only. |
| `avoidSeatUnitRefs` | array[string] | no | SeatUnits to avoid when known from prior failed attempts. |
| `preferenceVersion` | string | yes | Stable version/hash of the preference snapshot used for idempotency folding. |

### SeatRef

`SeatRef` is the credential display shape returned to Entitlement & Ticketing and
stored on issued credentials.

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Seat allocation ID (`salloc-<uuid>`). |
| `allocationType` | enum | yes | `SEAT`, `BERTH`, or `STANDING`. |
| `seatMapId` | string | no | SeatMap ID for concrete `SEAT`/`BERTH`; omitted for `STANDING`. |
| `seatMapVersion` | integer | no | SeatMap version for concrete `SEAT`/`BERTH`; omitted for `STANDING`. |
| `seatUnitRef` | string | no | Concrete SeatUnit for `SEAT`/`BERTH`; omitted for `STANDING`. |
| `coachNo` | string | no | Passenger-facing coach number for concrete allocations. |
| `seatNo` | string | no | Passenger-facing seat/berth number for concrete allocations. |
| `berthPosition` | enum | no | Present for berth allocations when known. |
| `displayLabel` | string | yes | Safe passenger-facing label, for example `05车 12A` or `STANDING`. |
| `degraded` | boolean | yes | True when a requested adjacency/berth/position preference was downgraded. |
| `degradationReason` | enum | no | Required when `degraded=true`. |

### SeatAllocation

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID (`salloc-<uuid>`). |
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`). |
| `journeyOrderId` | string | yes | Parent order ID (`ord-<uuid>`). |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>` or shared structured TravelerRef). |
| `segmentRef` | string | yes | Service segment reference. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `capacityHoldId` | string | yes | Capacity hold ID (`hold-<uuid>`). |
| `capacityUnitRef` | string | yes | Capacity unit reference from Capacity & Availability. |
| `interval` | object | yes | `StationInterval` with `fromSeq` and `toSeq`; half-open `[from,to)`. |
| `seatRef` | SeatRef | yes | Concrete or standing display fact. |
| `status` | enum | yes | SeatAllocation status. |
| `preferences` | SeatPreferences | no | Normalized preference snapshot used for solving. |
| `createdAt` | RFC3339 UTC | yes | Allocation creation timestamp. |
| `confirmedAt` | RFC3339 UTC | no | Entitlement confirmation timestamp. |
| `releasedAt` | RFC3339 UTC | no | Release timestamp. |
| `expiresAt` | RFC3339 UTC | no | Capacity hold expiry observed at allocation time. |

## Endpoints

### Create SeatMap from SIM seed

**POST** `/api/v1/seat-maps`

**Idempotency:** REQUIRED (`Idempotency-Key` UUID-v7-shaped header).

Creates a draft SeatMap from deterministic SIM composition seed data. Same
`scheduledServiceRef + serviceDate + compositionVersion + compositionSeed +
mappingVersion` must produce the same coach/SeatUnit topology.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `scheduledServiceRef` | string | yes | Scheduled service reference (`ss-<uuid>`). |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `compositionVersion` | string | yes | Operator/SIM composition version. |
| `compositionSeed` | string | yes | Deterministic seed; no real provider credentials or network endpoints. |
| `mappingVersion` | string | yes | Mapping version for SIM code to core SeatUnitRef. |
| `changeScenario` | string | no | Deterministic SIM scenario such as `BASE`, `COACH_REPLACED`, `SEAT_DISABLED`. |
| `operatorRef` | string | yes | Operator/admin actor reference; no personal documents. |

**Response (201):** `SeatMap` resource with `status=DRAFT` or `FAILED`.

**Error codes:** `VALIDATION_FAILED` (400), `CONFLICT` (409),
`IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422), `UNAVAILABLE`
(503).

### Publish SeatMap version

**POST** `/api/v1/seat-maps/{seatMapId}/publish`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedSeatMapVersion` | integer | yes | Optimistic version expected by the operator. |
| `publishReason` | string | yes | Operator reason; do not include sensitive personal data. |
| `operatorRef` | string | yes | Operator/admin actor reference. |

**Response (200):** `SeatMap` resource with `status=PUBLISHED`.

**Error codes:** `NOT_FOUND` (404), `PRECONDITION_FAILED` (412), `CONFLICT`
(409), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422),
`UNAVAILABLE` (503).

### Retire SeatMap version

**POST** `/api/v1/seat-maps/{seatMapId}/retire`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedSeatMapVersion` | integer | yes | Optimistic version expected by the operator. |
| `retireReason` | enum | yes | `SERVICE_ENDED`, `SUPERSEDED`, `OPERATOR_WITHDRAWAL`, `MANUAL_CORRECTION`. |
| `operatorRef` | string | yes | Operator/admin actor reference. |

**Response (200):** `SeatMap` resource with `status=RETIRED` or `SUPERSEDED`.

**Error codes:** `NOT_FOUND` (404), `PRECONDITION_FAILED` (412), `CONFLICT`
(409), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422),
`UNAVAILABLE` (503).

### Mark SeatUnit unavailable

**POST** `/api/v1/seat-maps/{seatMapId}/seat-units/{seatUnitRef}/mark-unavailable`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedSeatMapVersion` | integer | yes | Optimistic version expected by the operator. |
| `unavailableReason` | enum | yes | `MAINTENANCE`, `RESERVED`, `FAULT`, `OPERATOR_BLOCK`, `SIM_SCENARIO`. |
| `operatorRef` | string | yes | Operator/admin actor reference. |

**Response (200):** `SeatMap` resource after the SeatUnit is blocked.

**Error codes:** `NOT_FOUND` (404), `PRECONDITION_FAILED` (412), `CONFLICT`
(409), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422),
`UNAVAILABLE` (503).

### Reopen SeatUnit

**POST** `/api/v1/seat-maps/{seatMapId}/seat-units/{seatUnitRef}/reopen`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedSeatMapVersion` | integer | yes | Optimistic version expected by the operator. |
| `reopenReason` | enum | yes | `MAINTENANCE_CLEARED`, `RESERVATION_CLEARED`, `FAULT_CLEARED`, `MANUAL_CORRECTION`. |
| `operatorRef` | string | yes | Operator/admin actor reference. |

**Response (200):** `SeatMap` resource after the SeatUnit is assignable again.

**Error codes:** `NOT_FOUND` (404), `PRECONDITION_FAILED` (412), `CONFLICT`
(409), `IDEMPOTENCY_KEY_REUSED` (422), `DOMAIN_RULE_VIOLATION` (422),
`UNAVAILABLE` (503).

### Get SeatMap

**GET** `/api/v1/seat-maps/{seatMapId}`

**Response (200):** `SeatMap` resource. `RETIRED`, `SUPERSEDED`, and `FAILED`
SeatMaps remain queryable.

**Error codes:** `NOT_FOUND` (404).

### List SeatMaps

**GET** `/api/v1/seat-maps?scheduledServiceRef={scheduledServiceRef}&serviceDate={serviceDate}&limit=20&offset=0`

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `status` | enum | no | Optional SeatMap status filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `SeatMap` resource.

**Error codes:** `VALIDATION_FAILED` (400).

### Allocate seat for ticket issuance

**POST** `/api/v1/internal/seat-allocations`

**Idempotency:** REQUIRED. Caller is Entitlement & Ticketing in the issuance path.
The caller must persist and reuse the UUID-v7 key across retries. This endpoint is
internal to bounded-context integration and is not a customer seat-selection API.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`). |
| `journeyOrderId` | string | yes | Parent order ID (`ord-<uuid>`). |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Service segment reference. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `capacityHoldId` | string | yes | Capacity hold ID that already exists in Capacity & Availability. |
| `capacityUnitRef` | string | yes | Capacity unit reference from the hold/confirmation fact. |
| `interval` | object | yes | `StationInterval` with `fromSeq` and `toSeq`; half-open `[from,to)`. |
| `classRef` | string | yes | Required seat class/cabin; must match SeatMap/coach class. |
| `issuePurpose` | enum | yes | Entitlement purpose: `INITIAL`, `REPLACEMENT`, `MANUAL_RECOVERY`, `PROVIDER_REBUILD`, `DISRUPTION_REPLACEMENT`. |
| `seatPreferences` | SeatPreferences | no | Optional preferences; absence means no preference and `acceptStanding=false`. |
| `expiresAt` | RFC3339 UTC | yes | Capacity hold expiry known to the caller. |

**Response (201):**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID (`salloc-<uuid>`). |
| `status` | enum | yes | `ALLOCATED` or `STANDING`. |
| `seatRef` | SeatRef | yes | Display fact for Entitlement credential creation. |
| `expiresAt` | RFC3339 UTC | yes | Hold expiry copied from the request. |

**Error codes:** `VALIDATION_FAILED` (400), `NOT_FOUND` (404), `CONFLICT` (409),
`PRECONDITION_FAILED` (412), `IDEMPOTENCY_KEY_REUSED` (422),
`DOMAIN_RULE_VIOLATION` (422), `UNAVAILABLE` (503).

- `CONFLICT` is returned for overlapping concrete SeatUnit allocation or same
  idempotency key with an already committed but incompatible transition.
- `PRECONDITION_FAILED` is returned when the SeatMap version is stale or no
  published SeatMap matches the requested scheduled service/date/class.
- `DOMAIN_RULE_VIOLATION` is returned when no compatible seat exists and
  `seatPreferences.acceptStanding` is false.
- If `acceptStanding=true`, lack of concrete SeatUnit is a successful `STANDING`
  response, not an error.

### Get SeatAllocation

**GET** `/api/v1/seat-allocations/{seatAllocationId}`

**Response (200):** `SeatAllocation` resource. Terminal statuses remain queryable.

**Error codes:** `NOT_FOUND` (404).

### List SeatAllocations by segment booking

**GET** `/api/v1/seat-allocations?segmentBookingId={segmentBookingId}&limit=20&offset=0`

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`). |
| `status` | enum | no | Optional SeatAllocation status filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `SeatAllocation` resource.

**Error codes:** `VALIDATION_FAILED` (400).

## Bus-only behavior

The following lifecycle commands have no public HTTP endpoint in this activation
wave:

- `ConfirmSeatAllocation` — triggered by consuming `EntitlementIssued`.
- `ReleaseSeatAllocation` — triggered by consuming `EntitlementVoided`,
  `EntitlementIssueFailed`, `CapacityReleased`, or operator-approved correction.
- `ExpireSeatAllocation` — triggered by consuming `CapacityHoldExpired`.
- `AcceptAdjacencyDegradation` — internal solver/audit command when the policy
  accepts a downgraded adjacency outcome.
- `AppendSeatAllocationLedgerEvent` — internal immutable audit ledger append.

## Entitlement & Ticketing contract increment — needs same-wave implementation

The additions to `docs/08-contracts/api/entitlement-ticketing.md` and
`docs/08-contracts/events/entitlement-ticketing.md` are part of this Seat
Assignment activation and are **not docs-only**. They require same-wave code
changes in Entitlement & Ticketing at these touchpoints:

| Touchpoint | Required implementation change |
|---|---|
| Issue HTTP request DTO / deserializer | Accept optional `seatPreferences` using the field names and enums above. |
| Issue command validator | Validate `acceptStanding`, adjacency, seat position, and berth position enums; reject non-SCREAMING_SNAKE values. |
| Ticket issuance application service | Before committing the credential, call `POST /api/v1/internal/seat-allocations` with a persisted UUID-v7 idempotency key and propagate `corr-` / `cmd-` trace IDs. |
| Credential value object / projection | Persist and expose `seatRef` exactly as the `SeatRef` shape above, including `STANDING`. |
| Entitlement events outbox | Include `seatRef` and `seatAllocationId` on `EntitlementIssued`; include enough entitlement/segment reference for Seat Assignment to confirm or release. |
| Enum/schema tests | Add validation coverage for `allocationType`, `berthPosition`, `seatPosition`, `adjacencyPreference`, and `degradationReason`. |
