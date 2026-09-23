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

Evidence: revision 278 has 9163 failed journeys in a ten minute window; revision 279 passes all six purchase classes but has no stable full peak certification.
Sources: `.scratch/runtime-fixes/goal-rev278-ten-minute-window.json`, `goal-rev277-case-publication-diagnosis.json` and `goal-rev279-six-classes-live.log` under the same directory.

## Notification

### T02: Finish and release durable notification delivery

Status: confirmed notification delay; implementation remains uncommitted and undeployed in `services/notification/`.
The deployed path holds a database connection and recipient lock during SMTP.
Recorded order and post sales events remain without notification tasks after one minute; compensation credit notification samples have reached SMTP acceptance.

1. [ ] Review migration `006_durable_delivery.sql`, queue ownership, claim expiry, retry limits, recipient and channel quota reservation, and shutdown behavior.
2. [ ] Run the full notification suite and verify quota exhaustion followed by deferred recovery.
3. [ ] Verify SMTP uncertainty remains `Unknown`/`resultUnknown`, with no automatic resend or false success; verify late receipt reconciliation and stable Message-ID.
4. [ ] Verify task creation commits before SMTP, duplicate events create one task, and competing workers send only the claimed attempt.
5. [ ] Commit, migrate and deploy the completed implementation, then verify actual notification creation, receipt latency and backlog under resident load.

Existing evidence: TypeScript compilation and focused PostgreSQL/Mailpit tests pass, ten subtests plus parent, about 20.8 seconds.
The focused run includes 32 recorded tasks, delayed SMTP, refusal, result loss, late results and database lock release during sending.
It does not establish full module or deployed acceptance.

## Capacity and Booking

### T03: Complete coordinated supply integration

Status: uncommitted Capacity work and committed Booking source changes are not jointly activated.

1. [ ] Resolve accepted class, service, date, original purchase source, interval and composition for every active historical Hold; reject ambiguous source facts.
2. [ ] Define and enforce the binding between a Hold and its allocated physical unit, including historical references and composition replacement mappings.
3. [ ] Separate accepted travel class from occupancy class for standing substitution, and atomically reserve standing quota before allocation.
4. [ ] Derive availability and `CapacitySnapshotUpdated` from the same combined Hold and allocation occupancy across related pools and overlapping intervals.
5. [ ] Fence expired or released Holds against late confirmation or allocation and include unfinished allocation attempts in occupancy accounting.
6. [ ] Verify overlap, nonoverlap, physical classes, standing, composition changes, replay and concurrent release using recorded source facts.
7. [ ] Coordinate Booking and Capacity deployment only after these checks; retain the current Booking image boundary until then.

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

## Handoff rules

No additional implementation or deployment is requested by this documentation closeout.
Preserve unpublished Capacity and Notification changes in the shared worktree; do not include their source code in a documentation commit or deploy them implicitly.
On completion, attach code, deployment and business verification evidence, update the owning design and API documentation, and mark the checkbox here.
