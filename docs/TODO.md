# TODO

Updated: 2026-09-25, Asia/Beijing.

This is the single active backlog for the resident release. It records what is
still open, not what has been done: implemented and deployed behaviour lives in
[current status](00-current-status.md), and the telemetry contract lives in
[business metrics](07-observability/business-metrics.md).

An unchecked item is a confirmed gap or an unverified area. An unverified area
is not an assertion that the behaviour is wrong.

## Acceptance

### T01: Prove a clean resident cycle

Status: open until a full cycle on the current revision is recorded.

1. [ ] Keep one image set for a whole 600 second resident cycle and retain every
   request and journey record, including the recorder's drop counter.
2. [ ] Require zero unexpected HTTP errors, zero failed expected business
   journeys and no recorder drops, without shortening the workload or extending
   the client timeout.
3. [ ] Record the same window's consumer lag, pending entries and dead letters,
   and the money, capacity and wallet conservation checks.
4. [ ] Attribute any failure that does appear to an operation and an event
   timing rather than to a service name.

The standing policy and the reservation supply facts are deployed. The most
recent clean window was an eighteen minute run at revision 321 with zero failed
journeys and zero unexpected HTTP errors.

The reservation path now holds at the peak: a window inside the last run
carried zero failed journeys out of 10670, and the one after it 36 of 8924,
where the previous revision failed 12211 of 50345. What still fails is recorded
in [current status](00-current-status.md#the-resident-cycle-is-not-yet-clean):

1. [ ] Trip Planning's search must hold at the peak. Its query reads the plan
   corpus, and the load generator's own bootstrap adds to that corpus on every
   run -- `plan_nodes` reached 907825 rows and `plan_segments` 748331 when this
   was measured. A covering index took one search from 1366 to 130-210
   milliseconds, which held for a few windows and then degraded again as the
   corpus grew. Measured with the index in place: 56 searches a second, each
   fetching about 20000 segment rows, which is 1.1 million tuples a second from
   the plan tables alone.
2. [ ] Cut the remaining databases over to their shards. The chart defines six
   shards, but only six of the thirty-seven services set `pgInstance`; the other
   thirty-one use `core`, including every read-heavy one, and the read shard's
   copies of `trip_planning`, `offer_management`, `capacity_availability` and
   `seat_assignment` hold no tables at all. Core is doing the whole system's
   work: measured over 25 seconds it served 20886 fare-pricing transactions and
   12304 trip-planning ones, and 45.4 million trip-planning tuple fetches. The
   loads that need moving are the read shard's seven databases first, then the
   transactional shard's five; `reporting` is the worked example of the copy,
   verify and switch that this needs.
3. [ ] A change must be able to replace a reservation whose original hold is
   still active. The replacement is refused with `OVERLAPPING_HOLD` and the
   case answers 422, which the load generator records as `failed_at_change-execute`.
4. [ ] Disruption must not report a business conflict as an availability
   failure. Selecting an option for an order that already has a refund or change
   in progress answers 503 `UNAVAILABLE`; a conflict has its own status, and the
   flow has to decide what the honest terminal outcome for that case is.
5. [ ] Re-measure the refund and change `case-status` groups once the above are
   fixed; they wait on the reservations and cases in front of them.

## Open verification

### T03: Finish the coordinated supply integration

Status: activated; general multistop correctness is unproven.

1. [ ] Resolve accepted class, service, date, original purchase source,
   interval and composition for every active historical Hold; reject ambiguous
   source facts instead of guessing.
2. [ ] Define and enforce the binding between a Hold and its allocated physical
   unit, including historical references and composition replacement mappings.
3. [ ] Derive availability and `CapacitySnapshotUpdated` from the same combined
   Hold and allocation occupancy across related pools and overlapping intervals.
4. [ ] Fence expired or released Holds against late confirmation or allocation,
   and include unfinished allocation attempts in occupancy accounting.
5. [ ] Verify overlap, nonoverlap, physical classes, standing, composition
   changes, replay and concurrent release against recorded source facts.

Two stop intervals and the deployed standing policy do not prove general
multistop admission. See [capacity design](10-domain-enrichment/capacity-overbooking.md)
and [standing policy](08-contracts/standing-capacity-policy.md).

### T04: Verify automatic waitlist fulfillment

Status: remaining integration acceptance.

1. [ ] Match released capacity by date, class, interval and traveler intent.
2. [ ] Verify payment guarantees, offered window expiry, confirmation and
   duplicate promotion recovery.
3. [ ] Verify downstream ticket issuance and exactly one capacity and payment
   effect after a restart.

### T05: Prove durable event recovery

Status: incomplete acceptance.

1. [ ] Test Redis transport loss against an explicit replay source and
   retention policy. Short lived outbox and processed event records do not prove
   full historical recovery.
2. [ ] Attribute notification and loyalty dead letters before recovery actions
   and retain the original event and failure evidence.
3. [ ] Preserve terminal compensation mismatch checkpoints and verify that no
   completed refund or wallet credit is repeated.

### T06: Verify paid failure paths and business history

Status: unverified coverage.

1. [ ] Verify insurance claims and payouts, financial fees and reconciliations,
   group booking, corporate travel and bootstrap behaviour through the owning
   APIs. Insurance `PAID_OUT` and payout advice currently record a local
   decision without a Payment or Wallet disbursement call; verify the actual
   funding workflow before treating them as money delivered.
2. [ ] Verify eligibility and refunds after changed travel dates and after
   mixed or repeated fare difference payments.
3. [ ] Verify invoice amount basis and projection completion during a stable
   cycle.

### T07: Complete the reporting monetary edge cases

Status: explicit implementation limitations.

1. [ ] Define per currency totals instead of summing currencies into one scalar
   `totalRevenue` carrying the first currency label.
2. [ ] Define whether `totalRevenue` is a page total or a complete total, and
   validate pagination against that answer.
3. [ ] Make missing captured amount handling consistent between the PostgreSQL
   and in-memory aggregations, and test the chosen rule against real data.
4. [ ] Validate route, seat class, traveler type and attachment dimensions from
   authoritative business references.
5. [ ] Reconcile `orders_per_second` with its sixty second count, the trends
   with their single point, and the operational windows with the latest 10000
   event input cap.

### T08: Bound channel statement growth

Status: the storage shape is known; the growth rate is not.

Payment Channel stores statement lines in `channel_statement_snapshots` JSON.

1. [ ] Measure generation, freezing and discrepancy handling over a full
   retained day of real records.
2. [ ] If bounded latency cannot be met, normalise the lines while preserving
   statement identity, reconciliation facts and replay semantics.

### T09: Complete route feasibility and shared reference validation

Status: implementation gaps that need a scoped verification.

1. [ ] Consume and enforce published minimum connection time rules when general
   multileg search is enabled. The current direct segment search does not
   establish that capability.
2. [ ] Verify boundary prefix validation for shared reference wrappers;
   `shared-kernel-rust` generic IDs currently enforce non-empty text only.

### T10: Reconcile repository metadata and static checks

Status: tooling discrepancies, separate from runtime acceptance.

1. [ ] Reconcile the catalog's service entries with the business source
   directories, the image manifests and `go.work`, including Corporate Travel,
   Group Booking, Loyalty Membership, Marketing Campaign and Travel Insurance.
2. [ ] Validate the remaining conceptual catalogs against implemented APIs
   without turning scenario coverage into a claim of runtime completion.

Local Markdown links, catalog document paths and the runtime YAML values are
validated by the documentation checks. That is not a claim that every
repository check passes.

### T11: Verify wallet redemption policy enforcement

Status: the stored policy is not full external business validation.

1. [ ] Verify the applicable scope against the actual order or redemption
   business reference. The current command stores scope without resolving that
   external order.
2. [ ] Verify partial and reserved redemption, expiry and reversal preserve the
   intended available, reserved and redeemed balances across retries.

### T14: Verify change sagas finish inside the client polling window

Status: confirmed tail latency; about one percent of change cases finished
after the ten poll budget in the measured window.

1. [ ] Attribute the gap between the capacity hold request and the difference
   payment creation.
2. [ ] Reduce that gap below the polling budget without lengthening the client
   polling.
3. [ ] Re-measure the change journey duration distribution afterwards.

## Telemetry follow-ups

### T18: Finish low-cardinality business telemetry

Status: every source metric in the contract is implemented and deployed. What
remains is breadth and the follow-ups the new reads created.

1. [ ] Offer Management: publish the `search` and `quote` stages of the order
   funnel, which no service emits yet.
2. [ ] Port the Java event-consumer and outbox metric contract to the Go,
   Python, TypeScript and Rust platform kits, so the transport rules cover every
   service rather than the Java ones alone.
3. [ ] Add a cardinality test for each remaining source that proves two
   different business identifiers produce the same label set, and a restart
   test for every gauge that is derived from durable state.
4. [ ] Give the transport collector a scrape-side guard on its own series count,
   so a new stream or group cannot silently multiply the label set.
5. [ ] Add a collector that reports scheduled job success and the age of the
   oldest due task; several sweeps are only observable through their effects.

## Operational follow-ups

These are not defects in the code as shipped; they are the next step for a read
that was made safe rather than fast.

1. [ ] Payment conservation: the aggregate over `payment_intent_snapshots` is
   still planned as a parallel sequential scan even with the covering index
   present, because the snapshot writes keep the visibility map sparse.
   Measured: 33 seconds with the index, 59 without. Vacuuming the table, or
   maintaining the totals incrementally, would let the refresh return to a
   useful cadence.
2. [ ] Capacity levels: the fold streams rather than materialises, so it is
   constant memory, but it still reads seven hundred thousand pool documents
   (about five seconds). Pushing the fold into SQL would cut the transfer to the
   label combinations alone.
3. [ ] Notification backlog: the delivery policy cancels what has lost its
   value, and the queue depth and oldest age are now measured, so the remaining
   question is throughput against the per recipient daily quota rather than
   visibility.
4. [ ] Historical `processed_events.stream` rows written before the subscriber
   passed the stream to its handler are still empty. New rows carry the stream;
   the old ones are not worth rewriting beyond what the retention sweep removes.
5. [ ] Historical hold unit binding: a hold that predates `supplyEvidence`
   cannot name the composition its unit came from, so a unit ref that appears in
   more than one replacement lineage cannot be resolved. Admission now leaves
   such a hold out of the occupancy fold, on both sides of the check that
   compares the fold with the admission, rather than failing or inventing a
   unit. What that costs is the unit the hold used to stand for, so a pool whose
   composition was replaced can admit one more reservation than it should.
   Backfilling the binding from the owning composition would restore it.
6. [ ] Post Sales: `FarePricingAdjustmentQuoteClient.compute` answers 500
   `IllegalStateException: target offer travelers differ from the original
   order` when a change case's target offer does not carry exactly the order's
   traveler refs. Measured at 56 occurrences in three minutes while reservation
   failures were also happening, which is one candidate cause: a purchase whose
   reservation never confirmed has fewer tickets than the order has travelers.
   Re-measure once the reservation path is clean, and if it still occurs, decide
   whether the check belongs at offer creation or at evaluation.
7. [ ] `events:booking-orchestration:dlq` holds about eight thousand entries
   that exhausted their delivery attempts while capacity rejected reservation
   sources, which is a defect that is now fixed. They can never succeed and they
   are currently the largest dead-letter population in the release; decide
   whether to keep them as evidence or retire them before using that number as
   an alarm.

## Handoff rules

No unchecked item above is implicitly authorised: completing one means
implementing it, deploying it, and attaching the code, deployment and business
verification evidence to the item before it is marked done.

Runtime configuration is maintained in exactly one file,
`deploy/helm/train-ticket/values.yaml`. The former cluster, case, resident,
production and ACR overlays are deleted.
