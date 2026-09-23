# Current Project Status

Last verified: 2026-09-23.

## Runtime and deployment

Business services run in the `train-ticket` Kubernetes namespace through the Helm chart at `deploy/helm/train-ticket`.
`service-catalog.json` lists the business contexts, and `deploy/docker/` contains their image definitions.
Clients call each owning service's current HTTP APIs.
PostgreSQL stores business snapshots, transactional outboxes and processed event records.
Redis Streams carries events between services.
Persistent business data alone does not prove that every historical event can be replayed after transport data is lost.

The resident Go load generator runs from `deploy/loadgen-go` with the chart's mounted configuration.
The resident profile uses the `loadgen-resident-state` persistent claim, a 600 second diurnal period, `target_rps: 20` and a 10 second HTTP timeout.
Image updates must preserve this workload configuration and its persistent state.
See the [loadgen runtime guide](./README.md) for operation checkpoints and verification commands.

The verified release for the ticketing error mapping is Helm revision 257, and the accepted purchase source endpoint is deployed in revision 258.
Capacity change compensation is deployed in revision 259, with validated loadgen handling in revision 260.
Invoice creation reuses its transaction connection in revision 261.
Synchronous accepted order policy inputs are deployed in revision 262.
Elapsed difference payment handling is deployed in revision 263 and the recorded zero capture case completed compensation.
Expired target handling is deployed with loadgen in revision 264 and verified against a recorded case with unchanged original tickets.
Dedicated change execution lock connections and progress aware loadgen polling are deployed in revision 265.
The multi traveler purchase and post sales regression passes at that revision in 125.568 seconds.
Post Sales HTTP virtual thread handling is deployed in revision 266 and has passed a real HTTP test with 220 waiting request bodies.
Policy evaluation performs external reads outside database transactions in revision 267; eight concurrent evaluations return one persisted decision in its PostgreSQL test.
Seat allocation replay uses its recorded composition in revision 268, including when that composition has been retired.
The six class initial purchase regression at revision 268 passes in 97.152 seconds.
Revision 269 submits all traveler replacements before confirmation waits; its live partial paid change scenario validates submission order and complete compensation in 25.510 seconds.
Revision 270 records requested class and issue purpose for standing substitution replay, and its six class purchase test passes in 93.644 seconds.
Check `helm history` and the deployed image digests for the live release; repository HEAD and the running images can differ.

## Verified behavior

The following checks have completed against deployed services:

| Area | Evidence |
|---|---|
| Initial purchase classes | Second class, first class, business class, both sleeper classes and standing pass the live purchase test with matching issued allocations. |
| Standing quota | Published assignable second class supply supplies a ten percent quota rounded down; allocations include versioned policy evidence and no physical SeatUnit. |
| Eligibility | Quoted eligibility usage is confirmed and released through the purchase and post sales lifecycle. |
| Recovery | WAIT, REFUND, COMPENSATION and MANUAL selections retain their intended business outcomes. |
| Multiple travelers | Purchase, ancillary fulfillment, change, refund, compensation, cancellation and no show pass the combined live regression. |
| Protected transfer | A missed protected connection reaches the recorded recovery result. |
| Ticketing errors | A sold out Seat Assignment response reaches the ticketing caller as HTTP 422 with the original business error. |
| Capacity change compensation | Recorded sold out changes and a paid two traveler change with one issued replacement preserve original tickets, release replacement capacity and settle the full difference refund. |

The broad regression at revision 256 completed in 228.547 seconds.
The six class purchase regression at revision 257 completed in 92.007 seconds.
These tests establish their covered outcomes and do not certify every resident request or a complete peak traffic cycle.
The revision 262 broad regression passes eligibility, recovery, multiple traveler purchase and post sales, and protected transfer checks in 238.495 seconds.
The same broad regression at revision 264 passes in 225.767 seconds, including the deployed expiry handling and current loadgen behavior.
The broad regression passes at revision 266 in 239.548 seconds and at revision 267 in 230.630 seconds.
Revision 269's broad regression failed while waiting for a compensation case to complete; the recorded case later reached `APPLIED`.
Current service quality compensation still follows ticket cancellation and refund actions, so the preservation of original travel rights requires a separate business behavior correction.
Complete peak period and durable replay acceptance remain open.
The continuous 600 second recording ending at 12:58:47 UTC contains 711900 requests and 10348 failed journeys, with request P99 reaching the 10 second deadline.
That window spans several deployment revisions and is diagnostic evidence for peak latency.
Its recorder reports zero dropped records.
Identity verification, order creation and Post Sales requests account for the largest timeout groups.
The stable revision 264 window ending at 13:16:35 UTC contains 649717 requests and 2119 failed journeys, predominantly change completion and reservation confirmation waits.
Its request P99 is 5184 milliseconds.
The later five minute window ending at 13:26:15 UTC contains 147188 requests with zero unexpected HTTP errors or failed journeys; that window spans revisions 265 and 266 and is not a complete stable peak certification.
The continuous 600 second window ending at 13:36:16 UTC contains 427807 requests and 49191 journeys, with zero unexpected HTTP errors or failed journeys and request P99 of 243 milliseconds.
That window spans revisions 266 through 268, so stable release peak certification remains required.

## Supply integration

Seat Assignment's published capacity facts and standing quota enforcement are deployed.
Loadgen prepares the accepted composition before requesting reservations and uses the returned booking identity.
Service Plan exposes individual service segments and complete segment pagination for inventory reconciliation.

The [standing policy](08-contracts/standing-capacity-policy.md) specifies quota basis, rounding, admission, replay, release and evidence fields.
The [capacity integration requirements](10-domain-enrichment/capacity-overbooking.md) describe accepted booking sources, historical occupancy, persistence and activation checks.
Capacity's new combined supply calculation and the coordinated Booking behavior have not been activated in production.

Local PostgreSQL tests using deployed facts verify full business class refusal, Hold replay and release, standing admission, and complete recorded inventory across four pools containing 195 Holds.
Verified reservation source facts persist across query retries and service reconstruction.
The recorded cold query using the original purchase source took 8.22 seconds, with subsequent reads between 453 and 778 milliseconds.
These measurements are sample results and do not establish peak load latency.

Journey Order's accepted purchase source endpoint reads the original Offer from the persisted creation timeline fact.
Its real PostgreSQL HTTP test passes, and the deployed endpoint returns the recorded original Offer for an already changed order.
Historical purchase reconstruction must use the original accepted source when immutable booking supply references are absent.

## Active problems and remaining acceptance

Recorded sold out cases `psc-01a0cdac-da33-7fd4-a442-bdafdf12c14d` and `psc-01a0cdef-0d3c-7fa8-8b77-d13ed8e8766b` completed capacity compensation.
The paid partial issuance case `psc-01a0ce4b-329a-7f9c-8958-689fadc80160` verified replacement ticket voiding, full difference refund settlement, original ticket preservation and terminal replay.
Its settled difference refund is CNY 195.00.
These cases now end with `CHANGE_CAPACITY_UNAVAILABLE`, and loadgen records the checked outcome as `change_unavailable`.
The elapsed payment scenario `psc-01a0ce5a-4179-705d-92fa-a97251e0432d` completed `CHANGE_PAYMENT_TERMINATED` with zero captured funds and two preserved original tickets.
The expired target case `psc-01a0ce4d-dd0b-7fe3-8fd2-93a528ef2850` completed `CHANGE_OFFER_EXPIRED` without creating replacement bookings or a difference payment.

The remaining acceptance work includes:

1. Ordered route intervals, composition changes, standing substitutions and exact capacity ownership during coordinated Booking and Capacity activation.
2. Cross pool snapshot counts that agree with the combined admission calculation.
3. Automatic Waitlist release matching with correct date, class, payment guarantee and traveler intent.
4. Other paid failure paths and eligibility behavior after changed travel dates.
5. A complete peak traffic cycle, recorder completeness and durable consumer replay validation.
6. Notification and loyalty dead letter attribution, insurance claim history, finance history and daily fee checks, group and corporate workflows, reporting and bootstrap coverage.

An unmeasured area has no current all clear claim.
No workload reduction or relaxed error threshold is part of the acceptance criteria.

## Documentation and verification

Service README files describe their current implementation and test entry points.
`docs/08-contracts/api/` documents HTTP fields and errors, and `docs/08-contracts/events/` documents event payloads and consumer behavior.
Implementation changes must update the affected interface, service and operational documentation in the same issue commit when applicable.
Separate documentation corrections may be committed independently.
Every completion report must distinguish code tests, deployed image verification and live business acceptance.
