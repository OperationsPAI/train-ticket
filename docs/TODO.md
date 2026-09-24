# TODO

Updated: 2026-09-24, Asia/Beijing.

This is the single active backlog at the documentation handoff for Helm revision 279.
An unchecked item is a confirmed defect, unfinished implementation or unverified area, as stated below.
An unverified area is not automatically a confirmed defect.
Completed behavior and checks are recorded in [current status](00-current-status.md).

## Acceptance

### T01: Complete stable resident peak acceptance

Status: incomplete acceptance with recorded failures.

1. [ ] Keep one image set stable for the full 600 second resident cycle and retain all request and journey records, including recorder drop counts.
2. [ ] Attribute traveler creation, identity, risk, reservation confirmation, post sales completion and invoice readiness failures to actual operations and event timings.
3. [ ] Revalidate after Traveler Profile virtual threads and retention indexes; HTTP concurrency does not increase JDBC throughput by itself.
4. [ ] Require no unexpected HTTP errors, no failed expected business journeys and correct ticket, payment, capacity and wallet outcomes without reducing workload or extending timeouts.

Evidence: revision 278 has 9163 failed journeys in a ten minute window; revision 283 has 4 failed journeys in a complete ten minute cycle, all seat or standing rejections, so acceptance is close but not complete.
Sources: `.scratch/runtime-fixes/goal-rev278-ten-minute-window.json`, `goal-rev277-case-publication-diagnosis.json` and `goal-rev279-six-classes-live.log` under the same directory.

The peak phase on revision 290 shows the remaining systemic limit: several consumer groups fall behind while the load generator is at peak.
Measured lag at peak: customer service 15019, traveler profile 13551, journey order 11242 on the identity verification stream, and post sales 5178, reporting 3636 and identity verification 3536 on the journey order stream.
Reservation confirmations then exceed the load generator window, and the failures disappear once the peak passes.
Reducing peak load, extending polling or raising timeouts is not an acceptable remedy, so the consumer throughput itself has to improve.

Revision 291 repeats the pattern: a peak window failed 489 purchase and 25 transfer reservations while the following window had none, and the consumer lag fell back to 1502 and 3916 afterwards.
The next investigation must attribute the Booking saga confirmation latency at peak, because the reservation deadline depends on it.

Revision 292 attributed a worse burst to Traveler Profile: ten pooled connections with eight hundred waiting threads produced `Connection is not available, request timed out after 15000ms` and three thousand timed out traveler creations in four minutes.
After the pool increase the same route reports P99 of 164 milliseconds with no request above four seconds, and the following three minute window had eight failed journeys instead of five thousand.

Revision 297 measures the remaining limit with the consumers healthy: the resident cycle stays clean around thirty thousand requests per minute and starts failing near forty to one hundred thousand requests per minute, where the same services fall behind for one or two minutes.
Measured peaks: a clean five minute window carried 142326 requests with zero failures, while the two minute burst at 04:53 failed 911 journeys, mostly post sales case completion and reservation confirmation.
The failure window shows no single stalled consumer afterwards, so the next step is throughput per consumer at peak rather than a specific broken service.

Revision 300 attributes that limit: Customer Service fell twenty nine thousand events behind on the identity verification stream, Journey Order twenty seven thousand on the same stream, Entitlement Ticketing six thousand on the booking stream, and Traveler Profile's single subscriber thread could not follow either stream.
Raising those consumer threads, the service pools and the Identity Verification worker count moved the ten minute cycle to 14 failed journeys out of 41567, all of them the seat or standing rejection that T15 covers.
The tuning lives in the chart now, so a redeploy keeps it.

Revision 303 raised the published supply cache from two seconds to thirty seconds, which cut the peak reservation failures from 11092 in the revision 302 window to 125 and 236 in the two thirty second peak windows.
Revision 304 gave Seat Assignment a composition view and an occupancy view, and revision 305 made Capacity cache the composition until the occupancy reports another version; the second class admission payload fell from 383324 bytes to 13988 bytes.
The burst at 07:00 then exposed the next limit: Trip Planning's one GiB memory limit killed the pod with exit code 137, and the 400 seconds around it recorded 6727 search timeouts, 632 order snapshot failures and 245 change execution failures while Customer Service fell 26747 events behind on the identity verification stream.
Revision 306 raised that memory limit to 3Gi.
The next peak measurement must confirm that the Trip Planning restarts stop and that the identity verification consumers keep the backlog inside the reservation polling window.

## Notification

### T02: Finish and release durable notification delivery

Status: complete on revision 280.
Tasks, attempts and evidence commit before SMTP; SMTP runs outside the recipient transaction; quota exhaustion defers the task in the delivery queue with `QuotaDeferred` evidence; uncertain outcomes stay `Unknown` without automatic resend.

1. [x] Review migration `006_durable_delivery.sql`, queue ownership, claim expiry, retry limits, recipient and channel quota reservation, and shutdown behavior.
2. [x] Run the full notification suite and verify quota exhaustion followed by deferred recovery.
3. [x] Verify SMTP uncertainty remains `Unknown`/`resultUnknown`, with no automatic resend or false success; verify late receipt reconciliation and stable Message-ID.
4. [x] Verify task creation commits before SMTP, duplicate events create one task, and competing workers send only the claimed attempt.
5. [x] Commit, migrate and deploy the completed implementation, then verify actual notification creation, receipt latency and backlog under resident load.

Evidence: the full module suite is 109 tests with no failures and no skips, about 21 seconds (`.scratch/runtime-fixes/goal-notification-durable-full-tests.log`).
Migration `006_durable_delivery` was applied step by step to the resident database because the single transaction deadlocked with live traffic; the log is `.scratch/runtime-fixes/goal-notification-durable-migration-online.log`.
After deployment every subscribed stream reports zero lag and zero pending, the newest task age is zero seconds, and 13646 tasks were scheduled in five minutes.

## Capacity and Booking

### T03: Complete coordinated supply integration

Status: activated on revisions 294 to 305; the open requirement is a clean peak cycle.

The integration is live: the load generator presents the accepted class, scheduled service and date with each reservation, Booking persists them, and Capacity admits against Seat Assignment's published supply.
The pool snapshot stores unit counts, so one Hold rewrite is about five kilobytes instead of about one hundred and forty kilobytes.
The published supply is split into a composition that is cached until its version changes and an occupancy that is read on the admission interval, which cut the second class admission payload from 383324 bytes to 13988 bytes.
The Capacity consumer still reached 13730 events of lag in the measured burst, so its peak throughput stays the open requirement.

1. [ ] The Capacity consumer must keep zero lag on the Booking stream at peak.
2. [ ] Peak reservation latency must stay inside the load generator polling window in a single cluster rehearsal.
3. [ ] Resolve accepted class, service, date, original purchase source, interval and composition for every active historical Hold; reject ambiguous source facts.
4. [ ] Define and enforce the binding between a Hold and its allocated physical unit, including historical references and composition replacement mappings.
5. [ ] Separate accepted travel class from occupancy class for standing substitution, and atomically reserve standing quota before allocation.
6. [ ] Derive availability and `CapacitySnapshotUpdated` from the same combined Hold and allocation occupancy across related pools and overlapping intervals.
7. [ ] Fence expired or released Holds against late confirmation or allocation and include unfinished allocation attempts in occupancy accounting.
8. [ ] Verify overlap, nonoverlap, physical classes, standing, composition changes, replay and concurrent release using recorded source facts.
9. [ ] Keep Booking and Capacity deployed from the same source revision while these checks remain open.

Current two stop intervals and the deployed standing policy do not prove general multistop admission correctness.
See [capacity design](10-domain-enrichment/capacity-overbooking.md) and [standing policy](08-contracts/standing-capacity-policy.md).

### T04: Verify automatic Waitlist fulfillment

Status: remaining integration acceptance.

1. [ ] Match released capacity by date, class, interval and traveler intent.
2. [ ] Verify payment guarantees, offered window expiry, confirmation and duplicate promotion recovery.
3. [ ] Verify downstream ticket issuance and exactly one capacity/payment effect after restarts.

## Replay and historical outcomes

### T05: Prove durable event recovery

Status: incomplete acceptance.

1. [ ] Test Redis transport loss against an explicit replay source and retention policy; short lived outbox and processed event records do not prove full historical recovery.
2. [ ] Attribute notification and loyalty dead letters before recovery actions and retain original event and failure evidence.
3. [ ] Preserve terminal compensation mismatch checkpoints and verify no completed refund or wallet credit is repeated.

Three compensation cases completed with released original rights and no wallet issuance evidence are retained as failed audit outcomes.
Loadgen excludes those records from automatic execution; that does not mark compensation successful or authorize ticket recreation.

## Other business verification

### T06: Verify paid failure paths and business history

Status: unverified coverage, not an assertion that every path fails.

1. [ ] Verify insurance claims and payouts, financial fees and reconciliations, group booking, corporate travel and bootstrap behavior through owning APIs.
   Insurance `PAID_OUT` and payout advice currently record a local decision without a Payment or Wallet disbursement call; verify the actual funding workflow before treating them as money delivered.
   Finance reconciliation and supplier settlement query models do not establish an automatic scheduler; verify their invocation and saved business results.
2. [ ] Verify eligibility and refunds after changed travel dates and mixed or repeated fare difference payments.
3. [ ] Verify invoice amount basis and projection completion during the stable peak cycle after the deployed readiness and concurrency fixes.

### T07: Complete reporting monetary edge cases

Status: explicit implementation limitations.

1. [ ] Define per currency totals instead of summing currencies into scalar `totalRevenue` with the first currency label.
2. [ ] Define whether `totalRevenue` is a page total or a complete result total and validate pagination accordingly.
3. [ ] Make missing captured amount handling consistent between PostgreSQL and in memory aggregation and test the selected rule with actual source data.
4. [ ] Validate route, seat class, traveler type and attachment dimensions from authoritative business references.
5. [ ] Reconcile `orders_per_second` with its current sixty second count, trends with their current single point, and operational windows with the latest 10000 event input cap.

The gross captured correction and grouping sets migration are deployed; they do not fill missing dimensions or implement these separate cases.

### T08: Verify channel statement growth

Status: confirmed storage shape; current size and peak impact require measurement.
Payment Channel stores statement lines in `channel_statement_snapshots` JSON.

1. [ ] Measure generation, freezing and discrepancy handling with a full retained day of actual records.
2. [ ] If bounded latency cannot be met, normalize lines while preserving statement identity, reconciliation facts and replay semantics.

### T09: Complete route feasibility and shared reference validation

Status: implementation gaps requiring scoped verification.

1. [ ] Consume and enforce published minimum connection time rules when general multileg search is enabled; current direct segment search does not establish that capability.
2. [ ] Verify boundary prefix validation for shared reference wrappers; `shared-kernel-rust` generic IDs currently enforce nonempty text only.

Unrelated event types are intentionally acknowledged without business effects and are not a defect by themselves.

### T10: Reconcile repository metadata and static checks

Status: confirmed repository tooling discrepancies, separate from runtime acceptance.

1. [ ] Reconcile the 32 catalog service entries with the 37 business source directories and build manifests, including Corporate Travel, Group Booking, Loyalty Membership, Marketing Campaign and Travel Insurance.
2. [ ] Review the 35 findings from `python3 scripts/contract_lint.py` before changing code or rules; they include stream placement, an envelope field check and `units` checks that also match nonmonetary capacity data.
3. [ ] Validate remaining conceptual catalogs against implemented APIs without turning scenario coverage or phase metadata into a claim of runtime completion.

The documentation handoff validates local Markdown links, catalog document paths and unchanged runtime YAML values.
It does not claim the full repository code checks pass.

### T11: Verify wallet redemption policy enforcement

Status: stored policy is not full external business validation.

1. [ ] Verify applicable scope against the actual order or redemption business reference; the current command stores scope without resolving that external order.
2. [ ] Verify partial and reserved redemption, expiry and reversal preserve the intended available, reserved and redeemed balances across retries.

Approved complaint compensation issuance itself is deployed and verified; these checks concern subsequent use of the benefit.

### T12: Move payment channel calls out of database transactions

Status: complete on revision 318.
`captureIntent` and `requestRefund` now commit the channel order and submit keys in a short transaction, call the channel outside any transaction, and apply the handoff in a second short transaction.
The inbound event path uses the same shape and keeps its deduplication record in the same transaction as the business effect, so a failed handoff still retries.

1. [x] Persist the channel handoff keys in a short transaction, call the channel outside any transaction, and apply the result in a second short transaction.
2. [x] Preserve single channel submission per intent through the recorded order and submit keys, and verify concurrent capture and refund attempts.
3. [x] Re-measure payment tail latency and connection occupancy after the change.

Evidence: the module suite is 65 tests with no failures, including concurrent capture and refund cases and a live case that reads `pg_stat_activity` while the channel stub handles the request and finds no `idle in transaction` session (`.scratch/runtime-fixes/goal-t12-payment-tests-live-full.log`).
A negative control that moved the deduplication record back into the channel transaction reproduced the idle session and failed that assertion.
`OrphanRefundRecoveryService.recover` still reads post sales and calls the channel inside a transaction, and no test covers that entry point.

Evidence: with the default pool, `POST /api/v1/payment-intents` and `GET /api/v1/refunds` reached P99 of about five seconds and post sales and entitlement calls timed out; after revision 282 the same routes report P99 of 283 and 38 milliseconds.
The window is `.scratch/runtime-fixes/goal-rev281-payment-routes.json` and `.scratch/runtime-fixes/goal-rev282-payment-pool-window.json`.

The same transaction shape appears in other services: at revision 297 the Dispatch and Fulfillment databases held `idle in transaction` sessions for two to three minutes while their handlers were waiting on something else.
Those sessions keep a pooled connection and any row locks for the whole wait, which is a candidate explanation for the peak bursts recorded under T01.

### T14: Verify change saga completion inside the load generator polling window

Status: confirmed tail latency; about one percent of change cases finish after the ten poll budget.
Change execution is asynchronous: the load generator triggers execute-change once per second and stops after ten attempts.
Measured polls per case over five minutes: median three, ninety percent within eight, and twenty three cases needing all ten.

1. [ ] Attribute the gap between the capacity hold request and the difference payment creation, which reached nine seconds on the slowest sampled case.
2. [ ] Reduce that gap below the polling budget without lengthening the load generator polling.
3. [ ] Re-measure the change journey duration distribution after the change.

Evidence: `.scratch/runtime-fixes/goal-rev283-invoice-window.json` and case `psc-01a0d10a-6222-7fa8-b447-8a85283d859d`, whose hold was requested at 01:31:54.587 and whose difference payment was created at 01:32:03.846.

### T15: Reconcile standing acceptance with ticket issue

Status: confirmed rejection path; about one purchase in two hundred ends in a 422.
Entitlement ticketing answers 422 `no compatible seat exists and standing is not accepted` when the confirmed capacity hold has no matching physical seat and the issue command does not accept standing.

1. [ ] Determine whether the confirmed hold, the seat map composition and the issue command disagree about the occupancy class for that interval.
2. [ ] Make the accepted class, the reserved unit and the issued allocation agree, or reject the purchase before the payment is captured.

Evidence: `.scratch/runtime-fixes/goal-rev283-invoice-window.json` failure at 01:30:45.
The sampled case confirms the hold is `CONFIRMED` with capacity unit `24B` while Seat Assignment reports 123 units already taken for that service, date and interval, so the two domains disagree about remaining supply.
Seat Assignment only answers 422 when its own candidate list is empty; a preference that cannot be met degrades to an adjacent seat instead.

### T13: Apply the consumer recovery fix to the remaining Java images

Status: fix committed as `1f47d082`; only Booking carries it in production.
The same poll loop runs in journey order, payment, post sales, traveler profile, finance settlement, wallet promotion, group booking, admin audit and marketing campaign images.
Each image needs a rebuild from its own deployed baseline so that unrelated undeployed changes are not pulled in.

1. [ ] Rebuild each Java service from the commit its running image was built from, with only the java-kit recovery commit added.
2. [ ] Deploy them together during a maintenance window and confirm all consumer groups stay at zero lag with no poll failures.

### T16: Retire consumer groups whose subscriber no longer reads them

Status: complete.
`events:capacity-availability` held a `trip-planning` group and `events:journey-order` and `events:payment` held `travel-insurance` groups.
Trip Planning subscribes to `events:place-network` and `events:service-plan`, and Travel Insurance handles refund, delay and ancillary refund events, so neither group had a reader and each showed lag pinned to the stream retention limit.

1. [x] Confirm the owning service and configuration for each stale group, then delete the group or the abandoned pending entries.
2. [x] Record the decision so future log or lag reviews do not read these groups as stalled consumers.

Evidence: the three groups were deleted and a fresh `XINFO GROUPS` listing on the three streams no longer contains them (`.scratch/runtime-fixes/goal-stale-groups-cleanup.log`).

### T17: Enforce Booking saga step timeouts

Status: complete on revision 317.
Each step now records when its wait started, and a deadline index holds only the waits in flight.
A one second sweep fails overdue steps with `STEP_TIMED_OUT`, runs the recorded compensation, and leaves a terminal saga behind.

1. [x] Add a scheduled sweep in Booking Orchestration that fails steps past their deadline and runs the recorded compensation action.
2. [x] Make the owning change case terminal when its replacement reservation waits past the recorded deadline.
3. [x] Re-run the recorded change flow against a saga whose events never arrive, including the Booking sweep.

Evidence: the module suite is 94 tests with no failures, and the sweeper, its migration and its outbound events were exercised against the live database (`.scratch/runtime-fixes/booking-saga-timeout-sweep-tests.log`, `booking-saga-timeout-sweep-live-tests.log`).
Sagas that were already waiting before revision 317 carry no wait start and are not swept; case `psc-01a0d125-f1a7-7f93-884a-d626125a411f` is one of them and reaches its user visible outcome through the post sales deadline instead.

## Handoff rules

No additional implementation or deployment is requested by this documentation closeout.
Capacity changes are committed as `caafa668` and remain unpublished; do not deploy them implicitly.
Notification is deployed on revision 280 and Booking recovery on revision 281.
On completion, attach code, deployment and business verification evidence, update the owning design and API documentation, and mark the checkbox here.
