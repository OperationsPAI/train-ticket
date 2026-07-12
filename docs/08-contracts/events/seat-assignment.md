# Seat Assignment — Events & Commands

Last updated: 2026-07-09

## Scope and activation-wave rulings

This contract promotes the Seat Assignment lifecycle from
`docs/02-domains/seat-assignment.md` into cross-context wire contracts for
ADR-0003 wave A.

Activation-wave rulings:

- **RULING: SeatMap is operations CRUD.** SeatMap facts are produced only from
  operator/SIM seed commands in this wave. No real provider seat-map integration
  is active.
- **RULING: allocation happens in the Entitlement & Ticketing issuance path.**
  Entitlement calls `POST /api/v1/internal/seat-allocations`; Seat Assignment
  returns a `seatRef` for credential display. Entitlement contract increments are
  marked "needs same-wave implementation" in its API/events files and in
  `docs/08-contracts/api/seat-assignment.md`.
- `StandingAssigned` is a success fact. It is not a failed allocation and does
  not create a virtual SeatUnit.
- Adjacent allocation is best effort. Downgrade must be explicit in
  `AdjacentAllocationSolved` or `AdjacencyDegradationAccepted`.
- Seat Assignment consumes existing Capacity & Availability event names and
  fields: `CapacityReleased` (`holdId`, `inventoryPoolId`, `capacityUnitRef`,
  `segmentRef`, `interval`, `releasedAt`, `releaseReason`) and
  `CapacityHoldExpired` (`holdId`, `inventoryPoolId`, `capacityUnitRef`,
  `interval`, `expiredAt`) to release/expire matching seat assignments.

All payload fields are camelCase, enum values are SCREAMING_SNAKE_CASE, and all
timestamps are RFC3339 UTC. Envelope fields and optional trace context follow
`docs/08-contracts/messaging.md` and `docs/08-contracts/shared-primitives.md`.

## Event identity and idempotency

Seat Assignment producers MUST assign deterministic event IDs per aggregate
transition so retries do not create duplicate facts. The event ID seed is:

```
seat-assignment:<eventType>:<aggregateId>:<aggregateVersion>
```

The seed is SHA-256 folded, stamped with UUID version 7 and RFC-4122 variant bits,
and serialized with the `evt-<uuid-v7>` prefix in the envelope `eventId`. If a
replayed command or consumed upstream event applies no new transition, no new
event is emitted. Consumers still deduplicate by envelope `eventId`.

Command ids use `cmd-<uuid-v7>` and correlations use `corr-<uuid-v7>`. HTTP API
commands use the caller's UUID-v7 `Idempotency-Key` directly as the command key;
internal commands fold canonical materials as listed in the API contract. Same
key + same material replays the existing result. Same key + different material
emits or returns an idempotency conflict (`IDEMPOTENCY_CONFLICT` /
`IDEMPOTENCY_KEY_REUSED`) and must not mutate the previous allocation.

## Shared payload objects

### StationInterval

Seat Assignment uses the same interval semantics as Capacity & Availability:
half-open `[fromSeq,toSeq)`. Overlap exists when
`existingFromSeq < requestToSeq && requestFromSeq < existingToSeq`.

| Field | Type | Required | Description |
|---|---|---|---|
| `fromSeq` | integer | yes | Inclusive origin stop sequence. |
| `toSeq` | integer | yes | Exclusive destination stop sequence; must be greater than `fromSeq`. |

### SeatPreferences

| Field | Type | Required | Description |
|---|---|---|---|
| `acceptStanding` | boolean | yes | Whether a `STANDING` allocation is acceptable. |
| `adjacencyPreference` | enum | no | `NONE`, `SAME_COACH`, `SAME_ROW`, `ADJACENT`, `SAME_COMPARTMENT`. |
| `adjacencyGroupRef` | string | no | Client/order-level grouping reference for fellow travelers. |
| `preferredSeatPositions` | array[enum] | no | `WINDOW`, `AISLE`, `MIDDLE`, `LOWER_DECK`, `UPPER_DECK`. |
| `preferredBerthPositions` | array[enum] | no | `UPPER`, `MIDDLE`, `LOWER`, `SIDE_UPPER`, `SIDE_LOWER`. |
| `sameCompartment` | boolean | no | Best-effort berth grouping hint. |
| `avoidSeatUnitRefs` | array[string] | no | SeatUnit refs to avoid. |
| `preferenceVersion` | string | yes | Stable preference snapshot version used for idempotency folding. |

### SeatRef

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID (`salloc-<uuid>`). |
| `allocationType` | enum | yes | `SEAT`, `BERTH`, or `STANDING`. |
| `seatMapId` | string | no | SeatMap ID for concrete allocations. |
| `seatMapVersion` | integer | no | SeatMap version for concrete allocations. |
| `seatUnitRef` | string | no | Concrete SeatUnit for `SEAT`/`BERTH`; omitted for `STANDING`. |
| `coachNo` | string | no | Passenger-facing coach number. |
| `seatNo` | string | no | Passenger-facing seat/berth number. |
| `berthPosition` | enum | no | Present for berth allocations when known. |
| `displayLabel` | string | yes | Passenger-facing label such as `05车 12A` or `STANDING`. |
| `degraded` | boolean | yes | Whether preferences were downgraded. |
| `degradationReason` | enum | no | Required when `degraded=true`: `NO_ADJACENT_BLOCK`, `CLASS_MISMATCH`, `INTERVAL_CONFLICT`, `BERTH_PREFERENCE_UNAVAILABLE`, `STANDING_ASSIGNED`, or `POLICY_LIMIT`. |

## Published Events

### SeatMapBuilt

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | `BuildSeatMap` command accepted from `POST /api/v1/seat-maps`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | SeatMap ID (`smap-<uuid>`). |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `compositionVersion` | string | yes | Operator/SIM composition version. |
| `seatMapVersion` | integer | yes | SeatMap version after build. |
| `compositionSeed` | string | yes | Deterministic SIM seed. |
| `mappingVersion` | string | yes | SIM-to-core mapping version. |
| `coachCount` | integer | yes | Number of coaches built. |
| `seatUnitCount` | integer | yes | Number of concrete SeatUnits built. |
| `status` | enum | yes | `DRAFT`. |
| `builtAt` | RFC3339 UTC | yes | Build timestamp. |

### SeatMapBuildFailed

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | SIM seed validation or topology build failed deterministically. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | SeatMap ID allocated for the failed attempt. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `compositionVersion` | string | yes | Operator/SIM composition version. |
| `compositionSeed` | string | yes | Deterministic SIM seed. |
| `mappingVersion` | string | yes | Mapping version. |
| `failureReason` | enum | yes | `INVALID_SEED`, `DUPLICATE_SEAT_UNIT`, `CLASS_MAPPING_MISSING`, `STOP_SEQUENCE_MISMATCH`, `SIM_SCENARIO_UNSUPPORTED`. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `status` | enum | yes | `FAILED`. |

### SeatMapVersionPublished

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Operator publishes a validated SeatMap version. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | SeatMap ID. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `compositionVersion` | string | yes | Composition version. |
| `seatMapVersion` | integer | yes | Published version. |
| `publishedAt` | RFC3339 UTC | yes | Publish timestamp. |
| `operatorRef` | string | yes | Operator/admin actor reference. |
| `status` | enum | yes | `PUBLISHED`. |

### SeatMapVersionRetired

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Operator retires or supersedes a SeatMap version. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | SeatMap ID. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `seatMapVersion` | integer | yes | Retired/superseded version. |
| `retireReason` | enum | yes | `SERVICE_ENDED`, `SUPERSEDED`, `OPERATOR_WITHDRAWAL`, `MANUAL_CORRECTION`. |
| `retiredAt` | RFC3339 UTC | yes | Retirement timestamp. |
| `operatorRef` | string | yes | Operator/admin actor reference. |
| `status` | enum | yes | `RETIRED` or `SUPERSEDED`. |

### SeatUnitUnavailableMarked

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Operator marks a SeatUnit unavailable. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | SeatMap ID. |
| `seatMapVersion` | integer | yes | SeatMap version. |
| `seatUnitRef` | string | yes | SeatUnit reference. |
| `coachNo` | string | yes | Passenger-facing coach number. |
| `seatNo` | string | yes | Passenger-facing seat/berth number. |
| `unavailableReason` | enum | yes | `MAINTENANCE`, `RESERVED`, `FAULT`, `OPERATOR_BLOCK`, `SIM_SCENARIO`. |
| `markedAt` | RFC3339 UTC | yes | Mark timestamp. |
| `operatorRef` | string | yes | Operator/admin actor reference. |

### SeatUnitReopened

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Operator reopens a previously unavailable SeatUnit. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatMapId` | string | yes | SeatMap ID. |
| `seatMapVersion` | integer | yes | SeatMap version. |
| `seatUnitRef` | string | yes | SeatUnit reference. |
| `coachNo` | string | yes | Passenger-facing coach number. |
| `seatNo` | string | yes | Passenger-facing seat/berth number. |
| `reopenReason` | enum | yes | `MAINTENANCE_CLEARED`, `RESERVATION_CLEARED`, `FAULT_CLEARED`, `MANUAL_CORRECTION`. |
| `reopenedAt` | RFC3339 UTC | yes | Reopen timestamp. |
| `operatorRef` | string | yes | Operator/admin actor reference. |

### AdjacencyGroupCreated

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Allocation request includes an adjacency group for fellow travelers. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `adjacencyGroupId` | string | yes | Adjacency group ID (`adj-<uuid>`). |
| `journeyOrderId` | string | yes | Parent order ID. |
| `segmentRef` | string | yes | Segment reference. |
| `travelerRefs` | array[string] | yes | Sorted traveler references in the group. |
| `preference` | enum | yes | Requested adjacency preference. |
| `preferenceVersion` | string | yes | Preference snapshot version. |
| `status` | enum | yes | `OPEN`. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |

### AdjacentAllocationSolved

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | notification, reporting |
| **Trigger** | Seat Assignment solves an adjacency group with full or partial satisfaction. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `adjacencyGroupId` | string | yes | Adjacency group ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `segmentRef` | string | yes | Segment reference. |
| `seatAllocationIds` | array[string] | yes | Allocation IDs included in the result. |
| `result` | enum | yes | `SATISFIED`, `PARTIALLY_SATISFIED`, or `FAILED`. |
| `degraded` | boolean | yes | True when the requested adjacency was downgraded. |
| `degradationReason` | enum | no | Required when `degraded=true`. |
| `solvedAt` | RFC3339 UTC | yes | Solve timestamp. |
| `status` | enum | yes | `SATISFIED`, `PARTIALLY_SATISFIED`, or `FAILED`. |

### AdjacencyDegradationAccepted

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | notification, reporting |
| **Trigger** | Policy or caller accepts a best-effort adjacency downgrade. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `adjacencyGroupId` | string | yes | Adjacency group ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `seatAllocationIds` | array[string] | yes | Allocations proceeding after downgrade. |
| `acceptedByRef` | string | yes | `POLICY` or operator/caller reference. |
| `degradationReason` | enum | yes | Downgrade reason. |
| `acceptedAt` | RFC3339 UTC | yes | Acceptance timestamp. |

### AdjacencyGroupCancelled

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Upstream cancellation closes an open adjacency group. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `adjacencyGroupId` | string | yes | Adjacency group ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `cancelReason` | enum | yes | `BOOKING_CANCELLED`, `ISSUE_FAILED`, `MANUAL_CORRECTION`. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |
| `status` | enum | yes | `CANCELLED`. |

### BerthPreferenceRecorded

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Allocation request records berth-specific preferences. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `berthPreferenceRequestId` | string | yes | Berth preference request ID (`bpr-<uuid>`). |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `preferredBerthPositions` | array[enum] | yes | Ordered berth preferences. |
| `sameCompartment` | boolean | yes | Whether same-compartment solving was requested. |
| `preferenceVersion` | string | yes | Preference snapshot version. |
| `recordedAt` | RFC3339 UTC | yes | Record timestamp. |

### BerthPreferenceApplied

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Berth preference is applied or downgraded during allocation. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `berthPreferenceRequestId` | string | yes | Berth preference request ID. |
| `seatAllocationId` | string | yes | Allocation ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `requestedPositions` | array[enum] | yes | Requested berth positions. |
| `assignedPosition` | enum | no | Assigned berth position when concrete berth allocated. |
| `degraded` | boolean | yes | True when requested berth preference was not met. |
| `degradationReason` | enum | no | Required when degraded. |
| `appliedAt` | RFC3339 UTC | yes | Apply timestamp. |

### BerthPreferenceCancelled

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Upstream cancellation or manual correction cancels a berth preference request. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `berthPreferenceRequestId` | string | yes | Berth preference request ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `cancelReason` | enum | yes | `BOOKING_CANCELLED`, `ISSUE_FAILED`, `MANUAL_CORRECTION`. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |

### SeatAllocated

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | entitlement-ticketing, booking-orchestration, notification, reporting |
| **Trigger** | `AllocateSeat` succeeds with a concrete SeatUnit/berth. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID (`salloc-<uuid>`). |
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`). |
| `journeyOrderId` | string | yes | Parent order ID (`ord-<uuid>`). |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Segment reference. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `capacityHoldId` | string | yes | Capacity hold ID. |
| `capacityUnitRef` | string | yes | Capacity unit reference. |
| `interval` | StationInterval | yes | Station interval. |
| `seatRef` | SeatRef | yes | Concrete display fact; `allocationType` is `SEAT` or `BERTH`. |
| `preferences` | SeatPreferences | no | Normalized preference snapshot. |
| `allocatedAt` | RFC3339 UTC | yes | Allocation timestamp. |
| `expiresAt` | RFC3339 UTC | yes | Hold expiry known at allocation time. |
| `status` | enum | yes | `ALLOCATED`. |

### StandingAssigned

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | entitlement-ticketing, booking-orchestration, notification, reporting |
| **Trigger** | Allocation succeeds as no-seat standing because capacity exists and standing is accepted. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentRef` | string | yes | Segment reference. |
| `scheduledServiceRef` | string | yes | Scheduled service reference. |
| `serviceDate` | string | yes | Operating date in `YYYY-MM-DD`. |
| `capacityHoldId` | string | yes | Capacity hold ID. |
| `capacityUnitRef` | string | yes | Capacity unit reference. |
| `interval` | StationInterval | yes | Station interval. |
| `seatRef` | SeatRef | yes | `allocationType=STANDING`, `displayLabel=STANDING`, no `seatUnitRef`. |
| `preferences` | SeatPreferences | no | Normalized preference snapshot. |
| `assignedAt` | RFC3339 UTC | yes | Assignment timestamp. |
| `expiresAt` | RFC3339 UTC | yes | Hold expiry known at assignment time. |
| `status` | enum | yes | `STANDING`. |

### SeatAllocationConfirmed

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | booking-orchestration, notification, reporting |
| **Trigger** | Seat Assignment consumes `EntitlementIssued` for an active allocation. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID. |
| `entitlementId` | string | yes | Issued entitlement ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `seatRef` | SeatRef | yes | Credential display fact confirmed by Entitlement. |
| `confirmedAt` | RFC3339 UTC | yes | Confirmation timestamp. |
| `sourceEventId` | string | yes | Consumed `EntitlementIssued` envelope `eventId`. |
| `status` | enum | yes | `CONFIRMED`. |

### SeatAllocationReleased

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | booking-orchestration, notification, reporting |
| **Trigger** | Seat Assignment releases an allocation after `EntitlementVoided`, `EntitlementIssueFailed`, `CapacityReleased`, or manual correction. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `capacityHoldId` | string | yes | Capacity hold ID. |
| `seatRef` | SeatRef | yes | Seat/standing fact being released. |
| `releaseReason` | enum | yes | `VOIDED`, `CHANGED`, `ISSUE_FAILED`, `CAPACITY_RELEASED`, `DISRUPTION`, or `MANUAL_CORRECTION`. |
| `releasedAt` | RFC3339 UTC | yes | Release timestamp. |
| `sourceEventId` | string | no | Consumed upstream envelope `eventId`, required for event-driven releases. |
| `status` | enum | yes | `RELEASED`. |

### SeatAllocationExpired

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | booking-orchestration, notification, reporting |
| **Trigger** | Seat Assignment consumes `CapacityHoldExpired` for an active unconfirmed allocation. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `capacityHoldId` | string | yes | Expired hold ID. |
| `capacityUnitRef` | string | yes | Capacity unit reference from the consumed event. |
| `interval` | StationInterval | yes | Expired interval from Capacity. |
| `seatRef` | SeatRef | yes | Seat/standing fact being expired. |
| `expiredAt` | RFC3339 UTC | yes | Expiry timestamp from Capacity. |
| `sourceEventId` | string | yes | Consumed `CapacityHoldExpired` envelope `eventId`. |
| `status` | enum | yes | `EXPIRED`. |

### SeatAllocationFailed

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | entitlement-ticketing, booking-orchestration, reporting |
| **Trigger** | Allocation cannot be completed and standing is not an accepted success path. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID or requested allocation ID for the failed attempt. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `capacityHoldId` | string | no | Capacity hold ID when known. |
| `failureReason` | enum | yes | `NO_COMPATIBLE_SEAT`, `OVERLAPPING_ALLOCATION`, `CAPACITY_REFERENCE_MISSING`, `PREFERENCE_UNSATISFIABLE`, `SEAT_MAP_VERSION_STALE`, or `IDEMPOTENCY_CONFLICT`. |
| `retryable` | boolean | yes | Whether the caller may retry with a new command/key. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `status` | enum | yes | `FAILED`. |

### SeatAllocationMissed

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | booking-orchestration, reporting |
| **Trigger** | Allocation window or upstream saga already closed before Seat Assignment could act. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `missedReason` | enum | yes | `LATE_EVENT`, `SAGA_TERMINATED`, `SERVICE_DEPARTED`, `MANUAL_CUTOFF`. |
| `missedAt` | RFC3339 UTC | yes | Miss timestamp. |
| `sourceEventId` | string | no | Upstream event that caused the missed transition, if any. |
| `status` | enum | yes | `MISSED`. |

### SeatReassigned

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | entitlement-ticketing, booking-orchestration, notification, reporting |
| **Trigger** | Operator-approved or disruption-driven reassignment changes a concrete SeatUnit. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `seatAllocationId` | string | yes | Allocation ID. |
| `segmentBookingId` | string | yes | Segment booking ID. |
| `journeyOrderId` | string | yes | Parent order ID. |
| `travelerRef` | string | yes | Traveler reference. |
| `oldSeatRef` | SeatRef | yes | Previous seat display fact. |
| `newSeatRef` | SeatRef | yes | Replacement seat display fact. |
| `reassignReason` | enum | yes | `DISRUPTION`, `SEAT_UNAVAILABLE`, `MANUAL_CORRECTION`. |
| `reassignedAt` | RFC3339 UTC | yes | Reassignment timestamp. |
| `operatorRef` | string | no | Operator/admin actor reference for manual changes. |
| `status` | enum | yes | `ALLOCATED` or `CONFIRMED` depending on the previous lifecycle state. |

### SeatAllocationLedgerAppended

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Immutable allocation ledger records a lifecycle fact. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ledgerEventId` | string | yes | Ledger entry ID (`sled-<uuid>`). |
| `seatAllocationId` | string | yes | Allocation ID. |
| `ledgerEventType` | enum | yes | `ALLOCATED`, `STANDING_ASSIGNED`, `CONFIRMED`, `RELEASED`, `EXPIRED`, `FAILED`, `MISSED`, `REASSIGNED`. |
| `sourceEventId` | string | yes | Source envelope event ID or self event ID for the lifecycle fact. |
| `appendedAt` | RFC3339 UTC | yes | Append timestamp. |

### SeatAllocationCorrectionAppended

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Operator-approved manual correction is appended without rewriting history. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `ledgerEventId` | string | yes | Ledger entry ID. |
| `seatAllocationId` | string | yes | Allocation ID. |
| `correctionCaseId` | string | yes | Manual correction/audit case reference. |
| `correctionVersion` | integer | yes | Monotonic correction version for this allocation. |
| `correctionReason` | enum | yes | `DISPLAY_FIX`, `MANUAL_RELEASE`, `MANUAL_REASSIGN`, `AUDIT_REPAIR`. |
| `operatorRef` | string | yes | Operator/admin actor reference. |
| `appendedAt` | RFC3339 UTC | yes | Append timestamp. |

### SeatAllocationDiscrepancyDetected

| Field | Description |
|---|---|
| **Producer** | seat-assignment |
| **Consumers** | reporting |
| **Trigger** | Reconciliation detects mismatch between SeatMap occupancy, ledger, and upstream facts. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `discrepancyId` | string | yes | Discrepancy ID (`sdisc-<uuid>`). |
| `seatMapId` | string | yes | SeatMap ID. |
| `seatMapVersion` | integer | yes | SeatMap version checked. |
| `detectionRunId` | string | yes | Reconciliation run reference. |
| `discrepancyType` | enum | yes | `OVERLAP`, `MISSING_RELEASE`, `UNKNOWN_CAPACITY_HOLD`, `LEDGER_GAP`. |
| `seatAllocationIds` | array[string] | yes | Related allocation IDs; empty when none can be identified. |
| `detectedAt` | RFC3339 UTC | yes | Detection timestamp. |

## Accepted Commands

### AllocateSeat / AssignStanding

| Field | Description |
|---|---|
| **Sender** | entitlement-ticketing |
| **Transport** | Internal HTTP `POST /api/v1/internal/seat-allocations`; no event-bus command in this wave. |
| **Payload** | See `docs/08-contracts/api/seat-assignment.md` request table. |

### ConfirmSeatAllocation

| Field | Description |
|---|---|
| **Sender** | entitlement-ticketing via consumed `EntitlementIssued` event |
| **Trigger** | Entitlement credential committed with `seatRef`. |

**Payload material folded internally:** `seatAllocationId`, `entitlementId`, and
consumed envelope `eventId`.

### ReleaseSeatAllocation / ExpireSeatAllocation

| Field | Description |
|---|---|
| **Sender** | entitlement-ticketing or capacity-availability via consumed events |
| **Trigger** | `EntitlementVoided`, `EntitlementIssueFailed`, `CapacityReleased`, or `CapacityHoldExpired`. |

**Payload material folded internally:** `seatAllocationId` or `holdId`, release or
expiry reason, and consumed envelope `eventId`.
