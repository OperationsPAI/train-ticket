# Current project status

Last verified: 2026-09-24, Asia/Beijing.

## Runtime

The resident Helm release is `train-ticket` in namespace `train-ticket`, revision **279**.
PostgreSQL stores business snapshots, idempotency records, processed event records and transactional outboxes.
Redis Streams carries events; the current chart has no Redis persistent volume or AOF.
Database persistence alone does not prove recovery of every event after transport data is lost.

The resident Go load generator uses the `loadgen-resident-state` claim, a 600 second diurnal period, `target_rps: 20` and a 10 second HTTP request timeout.
Its configuration and persistent state remain unchanged by this documentation handoff.
No additional business deployment is part of this handoff.

## Implemented and deployed behavior

| Area | Current behavior |
|---|---|
| Service quality compensation | Approved `COMPENSATION` issues the approved currency and amount to the order account as wallet credit, preserving original tickets, seats, order and payment. |
| Wallet replay | Case identity and the full issuance request prevent duplicate credit, ledger entries and issuance events; conflicting requests are rejected. |
| Loadgen compensation | `compensation_credited` permits original ticket fulfillment; `compensation_failed` retains mismatch evidence and excludes automatic reexecution. |
| Ticket changes | Recorded operations preserve original rights when replacement capacity, payment or target validity prevents completion; captured difference refunds remain auditable. |
| Standing policy | Quota is ten percent of assignable second class supply, rounded down; standing has no physical SeatUnit. |
| Seat replay | Replay validates recorded class, issue purpose, preferences and original composition. |
| Service Plan | Segment supply returns ordered stops, interval and stop sequence version; current service creation uses two stops. |
| Search | The Rust implementation indexes candidates by date and origin node while preserving candidates and ranking. |
| Post Sales processing | Capacity release uses an independent subscriber; completed releases avoid order locks; evaluation performs external reads outside write transactions. |
| HTTP concurrency | Journey Order, Post Sales and Traveler Profile use virtual HTTP threads; database connection limits and dependency checks still apply. |
| Invoicing | Amount basis requires an order projection and matching expected total; order consumers run concurrently with per order locking. |
| Reporting | Monetary revenue fields represent gross `PaymentCaptured` amounts; breakdown dimensions share one grouping sets scan. |
| Retention | Published time indexes support existing outbox cleanup in Booking, Finance, Journey Order, Offer, Post Sales and Traveler Profile. |

See the [standing policy](08-contracts/standing-capacity-policy.md), [APIs](08-contracts/api/README.md), service README files and [retention evidence](09-performance/outbox-retention-indexes.md).

## Verification evidence

| Check | Result | Evidence |
|---|---|---|
| Six purchase classes on revision 279 | PASS, 86.305 seconds | `.scratch/runtime-fixes/goal-rev279-six-classes-live.log` |
| Broad business regression on revision 278 | PASS, 232.690 seconds | `.scratch/runtime-fixes/goal-rev278-broad-live.log` |
| Broad business regression on revision 279 | PASS, 245.364 seconds | `.scratch/runtime-fixes/goal-rev279-broad-live.log` |
| Wallet compensation and preserved fulfillment | PASS on revisions 274 and 275; latest focused run 17.184 seconds | `.scratch/runtime-fixes/goal-rev275-wallet-compensation-live.log` |
| Recorded invoice request | PASS after revision 276; issued invoice retained | `.scratch/runtime-fixes/goal-rev276-recorded-invoice-live.log` |
| Traveler local module suite | 59 passed, no skips, including 220 concurrent HTTP requests | `services/traveler-profile/target/surefire-reports/` |
| Reporting production migration | Three populated views committed in 10.359 seconds; source events preserved | `.scratch/runtime-fixes/goal-reporting-revenue-migration-online.log` |
| Stable full peak acceptance | Not established | [TODO](TODO.md#acceptance) |

The revision 278 ten minute recording ending at 2026-09-23 15:49:14 UTC contains 601683 requests and 9163 failed journeys, with request P99 of 9744.922 milliseconds.
The measured failures include traveler and identity timeouts and downstream waits.
The revision 279 two minute sample ending at 15:59:48 UTC contains 63364 requests, no unexpected HTTP errors or failed journeys, and P99 of 180.621 milliseconds.
These windows cover different releases and durations; the shorter result does not certify a complete peak cycle.
Scratch logs and module build outputs are local evidence and are not versioned repository artifacts.

## Workspace and deployment boundaries

| Component | Workspace state | Deployment state |
|---|---|---|
| Capacity supply integration | Implementation, migration and recorded tests are committed as `caafa668`. | Not activated. |
| Booking accepted supply | Main branch contains accepted source changes. | Running image remains based on `c5bb352d` plus compensation commit `1e62d420`; do not deploy main Booking independently. |
| Notification delivery queue | Queue, claim, SMTP and receipt changes are committed as `41917b85`; compile and focused PostgreSQL/Mailpit tests pass. | Not deployed; revision 279 still uses the transaction held sending path. |
| Notification verification | Ten focused subtests and their parent pass; full module tests and quota deferral recovery acceptance are incomplete. | Existing notification delay remains an active issue. |
| Online retention indexes | Verified in the owning databases and represented by migration files. | Some service images do not yet include their new migration file; online index creation did not modify migration version records. |

All remaining work, evidence and acceptance conditions are maintained only in [TODO](TODO.md).
This documentation handoff does not mark the full loadgen goal complete.
