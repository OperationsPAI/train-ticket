# Physical supply and standing quota integration

Last updated: 2026-09-24.

The combined Capacity supply calculation is an uncommitted workspace implementation and is not deployed.
Booking coordinated behavior is not enabled.
Seat Assignment's standing quota enforcement and pre-reservation SeatMap preparation are published and deployed.
Remaining work is tracked in [docs/TODO.md](../TODO.md).

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

The workspace implementation reads published physical units or the versioned standing quota for the requested service, date and class.
It includes active seat allocations and pending Capacity holds.
It uses the Hold identifier to deduplicate allocations already represented by a Hold and validates their order, booking, traveler and interval references.
It serializes admission by scheduled service, date and class and preserves the existing inventory transaction lock.
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
Original Hold identifiers and stored snapshots remain readable when supply facts are recorded and Holds are released.
HTTP and event reservation requests resolve the existing Hold by `segmentBookingId` under the reservation lock.
Different request keys for the same accepted booking retain one Hold identity and do not reacquire released capacity.
New Hold references store the actual `journeyOrderId` separately from `segmentBookingId`.
Historical source lookup runs before the inventory transaction, with at most eight concurrent upstream reservation lookups per service instance.
Each verified reservation is committed independently so a refused admission retains its source facts.
Repeated queries and process restarts reuse these immutable PostgreSQL records.
The locked admission query joins all active Holds to persisted reservation facts before reading current Seat Assignment supply.
An active Hold without persisted facts prevents admission.
Historical issued bookings can be resolved through their actual allocation and composition.
Initial purchase reservations without their own immutable references use Journey Order's `purchase-source` endpoint, which reads the accepted Offer from the persisted creation timeline fact.
Replacement reservations use the target Offer recorded by their change case.
Missing or inconsistent historical source facts prevent inventory admission.
Availability queries may persist source facts and supply observations, so they are not free of database writes.

## Current integration boundary

The workspace Capacity request path reads ordered interval facts from Service Plan's internal segment supply endpoint.
Service Plan currently creates two stop scheduled services, whose explicit zero based interval is `[0,1)`.
Capacity validates service date and existing Hold intervals against that source and records its stop sequence digest.
The current service creation API does not expose general multi stop route creation.
Capacity unit selection and Seat Assignment's physical allocation are separate operations; quantity admission currently does not establish exact unit identity agreement.
Combined availability queries and inventory pool snapshot events do not yet establish one consistent cross pool occupancy projection.
Accepted purchase class and standing substitution occupancy do not yet have an atomic quota transition.
Hold confirmation checks state without an independent expiry deadline check, so a late confirmation is not fully fenced by the timestamp alone.
The deployed ordinary `CapacityReleased` payload lacks the date required by Waitlist's release parser.
The local supply tests do not establish automatic waitlist fulfillment or replacement booking compensation under the new admission behavior.

## Existing verification evidence

Recorded PostgreSQL tests cover a full business class, availability count, a successful Hold lifecycle and replay of historical `standard` inventory.
The full business class sample has 36 physical units and 36 occupied units, so new admission must be refused.
Seat Assignment's standing test covers rounding, concurrent quota admission, replay and release.

The existing verification record reports online purchases for second class, first class, business class, both sleeper classes and standing using the Seat Assignment policy and loadgen preparation changes.
The Service Plan pagination endpoint exposes every persisted segment needed to locate historical inventory pools.
The existing Capacity record reports local PostgreSQL admission and replay tests using deployed supply facts.
The recorded inventory sample covers 195 Holds across four pools and checks that its active bookings have persisted source facts.
It verifies unchanged inventory records and repeated reads after service reconstruction.
The recorded run on 2026-09-23 using the deployed original purchase source took 8.22 seconds for the cold query and 453 to 778 milliseconds for subsequent queries.
These measurements cover the recorded sample and do not establish peak load latency.
They do not establish complete integration or activation of the new Capacity behavior.
No tests or deployment checks were rerun for this documentation update.
