# Current project status

Last verified: 2026-09-24, Asia/Beijing.

## Runtime

The resident Helm release is `train-ticket` in namespace `train-ticket`, revision **318**.
PostgreSQL stores business snapshots, idempotency records, processed event records and transactional outboxes.
Redis Streams carries events; the current chart has no Redis persistent volume or AOF.
Database persistence alone does not prove recovery of every event after transport data is lost.

The resident Go load generator uses the `loadgen-resident-state` claim, a 1800 second diurnal period, `target_rps: 20`, bursts between three and eight times the mean rate and a 10 second HTTP request timeout.
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
| Notification module suite | 109 tests, no failures, no skips, about 21 seconds | `.scratch/runtime-fixes/goal-notification-durable-full-tests.log` |
| Notification durable delivery on revision 280 | Every subscribed stream at zero lag and zero pending; newest task age 0 seconds; 13646 tasks in five minutes | `.scratch/runtime-fixes/goal-rev280-first-window.json` and the resident notification database |
| Booking consumer recovery on revision 281 | Lag fell from 30000 to 0 with no poll failures after deployment | `.scratch/runtime-fixes/goal-java-kit-recovery-tests.log` |
| Payment connection pool on revision 282 | `POST /api/v1/payment-intents` P99 fell from 5419 to 283 milliseconds; no request above four seconds | `.scratch/runtime-fixes/goal-rev281-payment-routes.json`, `goal-rev282-payment-pool-window.json` |
| Ten minute resident cycle at revision 283 | 384661 requests, 44212 journeys, P99 241 milliseconds, 4 failed journeys and 2 unexpected HTTP errors, all seat or standing rejections | `.scratch/runtime-fixes/goal-rev283-ten-minute-window.json` |
| Ten minute resident cycle at revision 300 | 362212 requests, 41567 journeys, P99 226 milliseconds, 14 failed journeys, all seat or standing rejections | `.scratch/runtime-fixes/goal-rev300-ten-minute.json` |
| Ten minute resident cycle at revision 306 | 451150 requests, 49821 journeys, P99 2888 milliseconds, 7143 failed journeys, 5634 reservation confirmations and 1331 post sales case status checks | `.scratch/runtime-fixes/goal-rev306-ten-minute.json` |
| Fifteen minute window at revisions 315 to 318 | 465276 requests, 53234 journeys, P99 354 milliseconds, 17 failed journeys, 15 boarding record races and 2 post sales executions | `.scratch/runtime-fixes/goal-rev317-fifteen-minute.json` |
| Eighteen minute window at revision 321 | 557863 requests, 64845 journeys, P99 257 milliseconds, 0 failed journeys and 0 unexpected HTTP errors | `.scratch/runtime-fixes/goal-rev321-eighteen-minute.json` |
| Stable full peak acceptance | Not established | [TODO](TODO.md#acceptance) |

The revision 278 ten minute recording ending at 2026-09-23 15:49:14 UTC contains 601683 requests and 9163 failed journeys, with request P99 of 9744.922 milliseconds.
The measured failures include traveler and identity timeouts and downstream waits.
The revision 279 two minute sample ending at 15:59:48 UTC contains 63364 requests, no unexpected HTTP errors or failed journeys, and P99 of 180.621 milliseconds.
Revision 280 kept every notification consumer at zero lag, and revision 281 restored the Booking reservation consumer after a BUSYGROUP recovery failure had stopped it for hours.
Revision 282 raised the payment connection pool after ten connections were observed fully busy with transactions waiting in `idle in transaction` during channel calls.
Revision 283 kept revenue recognition inside the invoice polling window, which removed the invoice amount basis failures that this cycle still shows at revision 282.
The remaining four failures in the ten minute cycle are `no compatible seat exists and standing is not accepted` rejections, tracked as T15 because Capacity and Seat Assignment disagree about remaining supply.
These windows cover different releases and durations; the shorter result does not certify a complete peak cycle.
Scratch logs and module build outputs are local evidence and are not versioned repository artifacts.

## Workspace and deployment boundaries

| Component | Workspace state | Deployment state |
|---|---|---|
| Capacity supply integration | Pool snapshots store unit counts, and the published supply is split into a cached composition and a per admission occupancy. | Activated in revisions 301 to 305. |
| Booking accepted supply | Main branch contains accepted source changes. | Running image remains based on `c5bb352d` plus compensation commit `1e62d420`; do not deploy main Booking independently. |
| Notification delivery queue | Tasks, attempts and evidence are committed before sending; SMTP runs outside the recipient transaction with stable message ids and `Unknown` for uncertain outcomes. | Deployed in revision 280 from `41917b85`. |
| Booking consumer recovery | Consumer groups are created once per stream and a failed pending-entry recovery no longer stops new reads; all subscribed streams are at zero lag. | Deployed in revision 281 from the pinned Booking source plus the java-kit fix. |
| Payment connection pool | The pool size is 32 instead of the default 10, which removes the multi-second waits that timed out post sales and entitlement calls. | Deployed in revision 282. |
| Finance settlement consumers | Eight consumer threads, a 32 connection pool and a two core limit keep revenue recognition inside the load generator polling window. | Deployed in revision 283. |
| Change reservation deadline | A change case whose replacement reservation never confirms now reaches `CHANGE_RESERVATION_TIMED_OUT` after fifteen minutes and the original tickets stay valid. | Deployed in revision 290. |
| Traveler Profile connection pool | The pool size is 40 instead of the default 10, which removes the burst wait that timed out traveler creation. | Deployed in revision 292. |
| Unpaid change Hold release | Compensation requests the release of a cancelled replacement whose Hold survived a transport loss. | Deployed in revision 293. |
| Reservation supply facts | The load generator presents the accepted class, scheduled service and date and Booking persists them. | Deployed in revisions 294 and 295. |
| Reservation supply views | Seat Assignment answers a composition view and an occupancy view, and Capacity caches the composition until its version changes. | Deployed in revisions 304 and 305. |
| Journey Order verification lookup | The traveler containment index covers only orders that are still open, so a verification event reads 19 blocks instead of 863. | Deployed in revision 306 as an online index. |
| Booking saga step deadline | Steps record when their wait started, and a one second sweep fails overdue steps with their recorded compensation. | Deployed in revision 317. |
| Payment channel handoff | The channel call runs outside any transaction, and the inbound path keeps its deduplication record with the business effect. | Deployed in revision 318. |
| Peak consumer tuning | Capacity availability, customer service, entitlement ticketing, finance settlement, post sales, reporting and traveler profile consume with more threads, the payment, finance settlement and traveler profile pools are larger, and Identity Verification runs eight workers over the same connection budget. | Deployed in revisions 286 to 300 and persisted in the chart. |
| Notification verification | Ten focused subtests and their parent pass; full module tests and quota deferral recovery acceptance are incomplete. | Existing notification delay remains an active issue. |
| Online retention indexes | Verified in the owning databases and represented by migration files. | Some service images do not yet include their new migration file; online index creation did not modify migration version records. |

## Coordinated supply integration

The integration runs on revisions 294 to 305.
The load generator presents the accepted class, scheduled service and date with each reservation and Booking persists them, so reservation admission reuses the accepted record instead of repeating upstream reads.
A Hold rewrite stores unit counts, about five kilobytes instead of about one hundred and forty kilobytes.
Capacity reads a composition view that is cached until its version changes and an occupancy view for the admission interval; the second class admission payload fell from 383324 bytes to 13988 bytes, measured with `.scratch/runtime-fixes/verify-capacity-facts-views.py`.
The remaining peak limit is the consumer backlog on the identity verification and Booking streams, which still exceeds the reservation polling window during the load generator's bursts.
The open requirements are recorded in [TODO](TODO.md#t03-complete-coordinated-supply-integration).

All remaining work, evidence and acceptance conditions are maintained only in [TODO](TODO.md).
This documentation handoff does not mark the full loadgen goal complete.
