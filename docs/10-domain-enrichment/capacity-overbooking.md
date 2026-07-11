# Capacity & Availability Enrichment — Overbooking & Dynamic Capacity

## Context

**Service**: capacity-availability (Rust, `services/capacity-availability/`)
**Current state**: 1,293-line domain in Rust with `CapacityPool`, `CapacityHold`, hold/confirm/release lifecycle, conflict detection, expiry management. This is already the richest domain model. Missing: overbooking strategy, dynamic capacity adjustment, waitlist trigger, capacity snapshots for fare-pricing.

**Key files**:
- `src/domain.rs` — core domain (1,293 lines)
- `src/application.rs` — application service
- `src/api.rs` — HTTP endpoints
- `src/ports.rs` — port definitions
- `migrations/` — SQL schema

**DDD spec**: `docs/02-domains/capacity-availability.md`

## Requirements

### R1: Overbooking Strategy

Real railway/airline systems intentionally sell more seats than physically available, banking on historical no-show rates:

```
overbooking_config:
  enabled: true
  max_overbooking_pct: 5     # sell up to 105% of physical capacity
  no_show_rate_default: 3.0  # historical 3% no-show rate
  safety_margin_pct: 1.0     # keep 1% buffer
  
  # Effective sellable = physical_capacity * (1 + overbooking_pct/100)
  # Example: 100 seats → 105 sellable
```

**Domain model changes in `domain.rs`**:
- Add `OverbookingPolicy` struct: `max_overbooking_pct: f64`, `no_show_rate: f64`, `safety_margin_pct: f64`
- Add `effective_capacity(&self) -> u32` method to `CapacityPool` that returns `physical_capacity * (1 + overbooking_pct)`
- `request_hold()` checks against `effective_capacity()` instead of `physical_capacity`
- When `confirmed_count > physical_capacity`, emit `OverbookingThresholdReached` event
- Add `OverbookingThresholdReached` domain event with `pool_id`, `physical_capacity`, `confirmed_count`, `overbooking_pct`

### R2: Dynamic Remaining Capacity Signal

Publish capacity snapshots for downstream consumers (fare-pricing dynamic pricing, trip-planning availability hints):

```
Event: CapacitySnapshotUpdated
Published after: every hold/confirm/release/expire operation
Fields:
  segmentRef: string
  departureDate: date
  totalCapacity: u32       # effective (with overbooking)
  remainingCapacity: u32   # effective - active holds - confirmed
  physicalCapacity: u32    # actual seats
  holdCount: u32
  confirmedCount: u32
  utilizationPct: f64      # (confirmed + holds) / effective * 100
  snapshotVersion: string
```

**Domain model changes**:
- Add `CapacitySnapshot` value object
- `CapacityPool` gains `snapshot(&self) -> CapacitySnapshot` method
- Every mutation method (`request_hold`, `confirm_hold`, `release_hold`, `expire_hold`) produces a `CapacitySnapshotUpdated` event alongside its existing event

### R3: Waitlist Trigger

When remaining capacity hits zero, signal the waitlist service:

```
Condition: remaining_capacity == 0 AND there are pending hold requests
Event: WaitlistActivated
Fields:
  segmentRef, departureDate, queuePosition: u32 (number of pending waitlisted)

Condition: capacity freed (release/expire) AND waitlist is active
Event: WaitlistCapacityFreed
Fields:
  segmentRef, departureDate, freedSlots: u32
```

**Domain model changes**:
- Add `WaitlistState` enum: `Inactive`, `Active { queue_size: u32 }`
- `CapacityPool` tracks `waitlist_state`
- When `request_hold()` fails due to no capacity: instead of returning error, return `HoldWaitlisted` result and emit `WaitlistActivated`
- When `release_hold()` or `expire_hold()` frees capacity AND `waitlist_state == Active`: emit `WaitlistCapacityFreed`

### R4: Capacity Pool Segmentation

Support multiple seat classes within a single segment:

```
CapacityPool structure:
  segment_ref: "seg-xxx"
  departure_date: "2026-07-20"
  classes:
    SECOND_CLASS:  { physical: 800, overbooking_pct: 5 }
    FIRST_CLASS:   { physical: 100, overbooking_pct: 3 }
    BUSINESS_CLASS: { physical: 20, overbooking_pct: 0 }
    STANDING:      { physical: 200, overbooking_pct: 10 }
```

**Domain model changes**:
- Split `CapacityPool` into `SegmentCapacity` (per segment) containing `ClassCapacity` sub-pools
- Hold requests specify `seat_class`
- Each class has its own overbooking policy, hold count, confirmed count
- The `CapacitySnapshotUpdated` event includes per-class breakdown

## Interface Contracts

### Events produced (new)
| Event Type | Trigger | Consumers |
|-----------|---------|-----------|
| `CapacitySnapshotUpdated` | Every capacity mutation | fare-pricing (dynamic pricing), trip-planning (availability hints), reporting |
| `OverbookingThresholdReached` | confirmed > physical | admin-audit, notification, disruption-recovery |
| `WaitlistActivated` | remaining == 0 | waitlist service |
| `WaitlistCapacityFreed` | release/expire when waitlist active | waitlist service |

### API changes
- `POST /api/v1/capacity/holds` request adds optional `seatClass: string`
- `GET /api/v1/capacity/segments/{segmentRef}/snapshot` — new endpoint returning current capacity snapshot with per-class breakdown
- `PUT /api/v1/capacity/segments/{segmentRef}/overbooking-policy` — configure overbooking params (admin)

## Test Criteria

1. Pool with 100 physical seats, 5% overbooking → accepts 105 holds
2. 106th hold request → WaitlistActivated event
3. Cancel 1 confirmed hold when waitlist is active → WaitlistCapacityFreed event
4. CapacitySnapshotUpdated emitted on every hold/confirm/release with correct counts
5. Per-class capacity: first-class full does NOT block second-class booking
6. OverbookingThresholdReached fires when confirmed > physical (but ≤ effective)

## Files to Modify

- `services/capacity-availability/src/domain.rs` — overbooking, snapshot, waitlist, class segmentation
- `services/capacity-availability/src/api.rs` — new snapshot endpoint, seat class parameter
- `services/capacity-availability/src/application.rs` — wire new events
- `services/capacity-availability/migrations/` — schema update for class-level pools
- `services/capacity-availability/tests/` — unit tests for overbooking logic
