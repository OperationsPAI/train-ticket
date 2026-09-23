# Physical supply and standing quota integration

Last updated: 2026-09-23.

## Sources

Service Plan owns scheduled service and segment timestamps.
Seat Assignment owns published compositions and actual seat or berth allocations.
Booking Orchestration records the accepted segment, traveler, class, service and date.
Capacity owns Hold admission, lifecycle, concurrency and availability observations.

The [Standing capacity policy](../08-contracts/standing-capacity-policy.md) defines a separate admission quota of ten percent of assignable second class seats, rounded down.
Concrete seats and berths cannot be increased by this quota.
The physical supply integration sets concrete overbooking to zero.
The domain's configurable overbooking type is not evidence of an available operator policy endpoint.

## Admission calculation

Read published physical units or the versioned standing quota for the requested service, date and class.
Include active seat allocations and pending Capacity holds.
Use the Hold identifier to deduplicate allocations already represented by a Hold.
Serialize admission by scheduled service, date and class and preserve the existing inventory transaction lock.
One traveler reservation requests exactly one capacity unit.
No remaining capacity produces the ordinary no capacity business outcome.
Dependency or inconsistent source errors remain errors and do not manufacture capacity.

Standing allocation has no SeatUnit or seat number.
Its internal quota reference is an admission slot, and policy evidence is stored separately from physical seat topology.
Standing snapshots report `physicalCapacity=0`; the separately calculated quota supplies `totalCapacity`.
`capacity_supply_observations.sellable_capacity` records the admissible total for both concrete units and standing.

## Persistence and replay

`reservation_supply_facts` records immutable accepted supply references per booking.
`capacity_supply_observations` records the map version and supply used for admission.
`InventoryPool.physical_units` separates current assignable supply from known historical unit references.
Original Hold identifiers and audit snapshots remain readable through migration and release.
Historical source lookup runs before the inventory transaction, with at most eight concurrent upstream reservation lookups per service instance.
Each verified reservation is committed independently so a refused admission retains its source facts.
Repeated queries and process restarts reuse these immutable PostgreSQL records.
The locked admission query joins all active Holds to persisted reservation facts before reading current Seat Assignment supply.
An active Hold without persisted facts prevents admission.
Historical issued bookings can be resolved through their actual allocation and composition.
Initial purchase reservations without their own immutable references use Journey Order's `purchase-source` endpoint, which reads the accepted Offer from the persisted creation timeline fact.
Replacement reservations use the target Offer recorded by their change case.
Missing or inconsistent historical source facts prevent inventory admission.

## Verification and activation requirements

Recorded PostgreSQL tests cover a full business class, availability count, a successful Hold lifecycle and replay of historical `standard` inventory.
The full business class sample has 36 physical units and 36 occupied units, so new admission must be refused.
Seat Assignment's standing test covers rounding, concurrent quota admission, replay and release.

Production activation also requires the following evidence:

1. SeatMaps are published before inventory reservation, including the composition used for standing quota.
2. Initial and replacement bookings carry accepted class, service and date references.
3. All historical active holds are included across related segments and service dates.
4. Ordered intervals, published map changes and overlapping occupancy are handled consistently.
5. A no capacity outcome cancels replacement reservations while preserving the original ticket and refunding any captured difference payment.
6. Waitlist consumes actual release facts with matching class, date and traveler intent.
7. Snapshot events report the same combined occupancy used for admission across related inventory pools.
8. Replayed reservations, standing substitutions and published composition changes retain consistent capacity ownership.

The current Capacity request path uses interval `[0,1)`.
The domain's general overlap tests do not establish ordered route interval integration.
Capacity unit selection and Seat Assignment's physical allocation are separate operations; quantity admission currently does not establish exact unit identity agreement.
Complete these checks before activating the new admission path.

The Seat Assignment policy and loadgen preparation changes have passed online purchases for second class, first class, business class, both sleeper classes and standing.
The Service Plan pagination endpoint exposes every persisted segment needed to locate historical inventory pools.
The new Capacity calculation has passed local PostgreSQL admission and replay tests using deployed supply facts.
The complete recorded inventory test covers 195 Holds across four pools and checks that all active bookings have persisted source facts.
It verifies unchanged inventory records and repeated reads after service reconstruction.
The recorded run on 2026-09-23 using the deployed original purchase source took 8.22 seconds for the cold query and 453 to 778 milliseconds for subsequent queries.
These measurements cover the recorded sample and do not establish peak load latency.
Production Capacity and the coordinated Booking behavior have not yet been switched to this calculation.

The supply integration is under implementation and is not established as complete by the currently passing narrow tests.
Check the rendered deployment image and live business regression before declaring the new inventory behavior active.
