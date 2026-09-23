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

The broad regression at revision 256 completed in 228.547 seconds.
The six class purchase regression at revision 257 completed in 92.007 seconds.
These tests establish their covered outcomes and do not certify every resident request or a complete peak traffic cycle.

## Supply integration

Seat Assignment's published capacity facts and standing quota enforcement are deployed.
Loadgen prepares the accepted composition before requesting reservations and uses the returned booking identity.
Service Plan exposes individual service segments and complete segment pagination for inventory reconciliation.

The [standing policy](08-contracts/standing-capacity-policy.md) specifies quota basis, rounding, admission, replay, release and evidence fields.
The [capacity integration requirements](10-domain-enrichment/capacity-overbooking.md) describe accepted booking sources, historical occupancy, persistence and activation checks.
Capacity's new combined supply calculation and the coordinated Booking behavior have not been activated in production.

Local PostgreSQL tests using deployed facts verify full business class refusal, Hold replay and release, standing admission, and complete recorded inventory across four pools containing 195 Holds.
Verified reservation source facts persist across query retries and service reconstruction.
The recorded cold query took 5.29 seconds, with subsequent reads between 346 and 402 milliseconds.
These measurements are sample results and do not establish peak load latency.

Journey Order's accepted purchase source endpoint reads the original Offer from the persisted creation timeline fact.
Its real PostgreSQL HTTP test passes, and the deployed endpoint returns the recorded original Offer for an already changed order.
Historical purchase reconstruction must use the original accepted source when immutable booking supply references are absent.

## Active problems and remaining acceptance

Sold out replacement changes still return an unexpected HTTP 500 from Post Sales.
Two recorded cases are `psc-01a0cdac-da33-7fd4-a442-bdafdf12c14d` and `psc-01a0cdef-0d3c-7fa8-8b77-d13ed8e8766b`.
The relevant business class has 36 physical seats already occupied while a replacement Hold was confirmed.
Ticketing now preserves the business rejection; Post Sales still needs a terminal compensation result that preserves original tickets and refunds any captured difference payment.

The remaining acceptance work includes:

1. Ordered route intervals, composition changes, standing substitutions and exact capacity ownership during coordinated Booking and Capacity activation.
2. Cross pool snapshot counts that agree with the combined admission calculation.
3. Automatic Waitlist release matching with correct date, class, payment guarantee and traveler intent.
4. Partial paid failure compensation and eligibility behavior after changed travel dates.
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
