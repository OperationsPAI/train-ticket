# Seat Assignment implementation

Last updated: 2026-09-24.

## Runtime and model

The Go service in `services/seat-assignment` uses PostgreSQL transactions and a Redis outbox relay.
The domain boundary is documented in [Seat Assignment](../02-domains/seat-assignment.md).
Remaining work is tracked in [docs/TODO.md](../TODO.md).

The service exposes published SeatMaps and internal allocations through `ContractSeatMap` and `ContractSeatAllocation`.
It also exposes the segment and date based `SeatInventory` and `SeatAssignment` API.
These models have different request shapes and allocation algorithms.
The internal ticket allocation path requires a published composition and explicit booking, order, traveler, Hold, service, date, class and interval references.

## Published compositions

`internal/domain/seatmap_contract.go` builds deterministic SIM compositions.
The material includes the scheduled service, date, composition version, seed and mapping version.
Repeated creation with the same material preserves map and unit identities.
A published map rejects unit mutation, and recorded allocations retain their original map references.

The `CRH380A` seed uses the configuration in `internal/domain/train_config.go`.

| Coaches | Class | Layout | Rows per coach | Seats per coach |
|---|---|---|---|---|
| 1 | `BUSINESS_CLASS` | 2+1 | 12 | 36 |
| 2 through 5 | `FIRST_CLASS` | 2+2 | 20 | 80 |
| 6 through 16 | `SECOND_CLASS` | 3+2 | 25 | 125 |

The `SLEEPER` seed creates `SLEEPER_HARD` and `SLEEPER_SOFT` coaches with explicit berth positions.
Hard sleeper has 54 berths and soft sleeper has 36 berths in this simulated composition.
These are generated platform inputs, not verified railway vehicle specifications.

## Allocation and concurrency

`internal/application/seatmap_service.go` implements the internal allocation endpoint.
It selects from published maps, filters occupied or avoided units, and evaluates adjacency against existing members of the requested group.
A PostgreSQL transaction advisory lock serializes allocation for a scheduled service and date.
The active allocation query includes `ALLOCATED`, `STANDING` and `CONFIRMED` records.
Concrete unit conflicts use overlapping half open station intervals.

The internal endpoint allocates one traveler per call.
It records adjacency degradation and can assign standing when the request permits it and quota remains.
Its selector does not score all recorded seat position or berth position preferences.
The separate `AssignmentEngine` used by `/seat-assignments` supports window, aisle, quiet car, forward facing and group scoring.
That algorithm does not establish equivalent behavior for internal ticket allocations.

An active allocation replay checks booking, order, traveler, Hold, capacity reference, segment, interval, requested class, issue purpose, preferences and expiry.
A matching replay returns the recorded allocation without consuming another seat or standing slot.
The endpoint requires capacity references but does not synchronously verify the Hold with Capacity.
It requires a nonzero expiry timestamp but does not check that timestamp against the current time before allocation.
The allocated `seatRef.seatUnitRef` is selected locally and need not equal the submitted `capacityUnitRef`.

## Standing policy

The [Standing capacity policy](../08-contracts/standing-capacity-policy.md), identified by `standing-second-class-10pct-v1`, is published and enforced by Seat Assignment.
The limit is ten percent of assignable second class seats in the selected published map, rounded down.
Occupied second class seats remain in the basis.
Missing second class composition prevents standing allocation.

Direct standing purchases and accepted standing substitutions share the interval occupancy limit.
Historical active standing allocations count even when they lack policy metadata.
A standing allocation carries `standingPolicy` evidence and the composition reference.
Its `seatRef` has `allocationType=STANDING` and `displayLabel=STANDING`, with no `seatUnitRef`, `coachNo` or `seatNo`.
`requestedClassRef`, `issuePurpose` and accepted preferences preserve the original purchase intent when a substitution is replayed.

Seat Assignment currently counts its own active allocations.
Capacity's combined calculation of allocations and pending Holds is committed as `caafa668` and remains undeployed.
Booking coordinated behavior is not enabled.
Published standing enforcement does not establish completed combined supply admission.

## APIs and events

The [HTTP API](../08-contracts/api/seat-assignment.md) defines the public fields.
The runtime registers SeatMap creation, listing, reading, publication, retirement and unit availability operations.
`POST /api/v1/internal/seat-allocations` creates ticket allocations.
`GET /api/v1/seat-allocations` and the identifier endpoint read persisted results.

`GET /api/v1/internal/capacity-facts` returns the selected published map, assignable units and active allocations for a service, date and class.
For `STANDING`, it returns an empty units array, standing policy evidence and active standing allocations.
Concrete allocations are resolved through their recorded composition, including historical map references.

The service publishes `SeatMapBuilt`, `SeatMapVersionPublished`, `SeatMapVersionRetired`, unit availability events, `SeatAllocated` and `StandingAssigned`.
Entitlement issuance confirms allocations.
Entitlement voiding, issuance failure and applicable post sales events release them.
Capacity release and expiry match allocations by Hold identity.
Seat release does not itself release the Capacity Hold.

Subscribed streams are Booking, Entitlement, Post Sales and Capacity.
There is no Service Plan event handler that automatically publishes SeatMaps.
Internal allocation states are `ALLOCATED`, `STANDING`, `CONFIRMED`, `RELEASED` and `EXPIRED`.
Rejected HTTP requests do not imply a persisted allocation failure record.

## Existing verification

Existing domain and application tests cover composition generation, interval conflicts, allocation replay, map publication and allocation messages.
The PostgreSQL tests in `internal/adapters/storage` exercise allocation concurrency, supply facts, map replay and standing quota behavior.
`standing_policy_live_test.go` covers rounding, concurrent admission, stored policy evidence, replay and release using recorded composition data.
These tests require their recorded inputs and a disposable database.

The published standing policy records online purchase checks across seated, sleeper and standing classes.
Those checks establish the documented Seat Assignment behavior within their executed scope.
They do not establish arbitrary multi stop sales, atomic group allocation or deployment of Capacity's combined supply path.
No tests were added or rerun for this documentation update.
