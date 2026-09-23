# Standing capacity policy

Last updated: 2026-09-23.

## Policy

The policy identifier is `standing-second-class-10pct-v1`.
This is a Train Ticket simulation policy approved for implementation by the project owner.
It is not a statement of a railway operator's safety certification or passenger limit.

For each scheduled service and service date, select the published SeatMap used for second class allocation.
Count assignable SeatUnits in assignable `SECOND_CLASS` coaches.
The standing limit is `floor(basisUnits / 10)`.
Occupied second class seats remain part of the basis; sales do not change the standing limit.
Business class, first class, berths and unavailable units do not contribute to the basis.
No applicable published composition means that a standing allocation is unavailable.
Fewer than ten assignable second class seats gives a standing limit of zero.

Standing capacity is an independent quota.
It does not permit overselling concrete seats or berths.
It never creates a physical SeatUnit or a passenger seat number.
Capacity's `quota:<policyVersion>:<index>` identifiers represent admission slots only.

## Admission and lifecycle

Standing admission counts the maximum simultaneous standing occupancy within the requested half open station interval.
Both direct `STANDING` purchases and accepted standing substitutions consume this quota.
Existing `STANDING` and `CONFIRMED` standing allocations count, including historical allocations without policy metadata.
Pending Capacity holds also count before ticket allocation.
The same hold is counted once when both services have recorded it.

Seat Assignment serializes admission for a scheduled service and date.
A repeated successful reservation returns its original allocation without taking another slot.
A new request at the limit returns `DOMAIN_RULE_VIOLATION` with `standing quota is exhausted`.
Release or expiry makes the quota available again.
The existing Capacity hold lifecycle remains authoritative for releasing holds.

A published SeatMap is immutable.
An operator publishes a replacement composition to change the basis for new admissions.
Existing allocations retain their recorded map, policy and identity.
If the new limit is below existing occupancy, further admission is refused until occupancy falls below the limit.
No existing ticket is silently cancelled or converted to a seated ticket.

## Evidence fields

`SeatAllocation.standingPolicy` and the `StandingAssigned` event contain:

| Field | Value |
|---|---|
| `version` | `standing-second-class-10pct-v1` |
| `basisClass` | `SECOND_CLASS` |
| `basisUnits` | Assignable second class units in the selected published map |
| `percent` | `10` |
| `limit` | `floor(basisUnits / 10)` |

The allocation's `seatRef.seatMapId` and `seatMapVersion` identify the policy's composition.
`seatRef.allocationType` is `STANDING` and `displayLabel` is `STANDING`.
`seatUnitRef`, `coachNo` and `seatNo` are absent for standing.

`GET /api/v1/internal/capacity-facts` returns this policy for `classRef=STANDING`, an empty `units` array and active standing allocations.
The Capacity consumer validates the policy version and arithmetic before using the limit.
Unavailable dependencies do not produce a default quota.

## Verification

`services/seat-assignment/internal/adapters/storage/standing_policy_live_test.go` loads recorded composition and standing reservations into isolated PostgreSQL tables.
It checks rounding, concurrent admission, durable policy metadata, replay, release and read side effects.
The quota boundary is exercised by marking recorded second class units unavailable through the domain operation before publishing the test composition.

`services/capacity-availability/tests/standing_supply_live.rs` verifies policy validation, quota references, Hold admission, replay, confirmation and release against PostgreSQL and actual upstream facts.
It verifies that a standing snapshot reports zero physical seats and that quota confirmation does not emit a physical seat overbooking event.
The six class online purchase test verifies standing policy metadata on issued allocations.
Seat Assignment quota enforcement and pre-reservation SeatMap preparation are deployed and verified.
Capacity's new supply calculation remains under integration and has not been activated in production.
Its rollout requires coordinated reservation facts, SeatMap preparation and historical occupancy validation.
Passing the Seat Assignment test alone does not establish that the complete booking flow is ready.
