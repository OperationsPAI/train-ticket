# TODO

Updated: 2026-09-26, Asia/Beijing.

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

The reservation path holds below the burst: a window inside an earlier run
carried zero failed journeys out of 10670, and the one after it 36 of 8924,
where the revision before that failed 12211 of 50345. What still fails is
recorded in [current status](00-current-status.md#the-resident-cycle-is-not-yet-clean):

1. [ ] Trip Planning's search must hold at the peak. Its query reads the plan
   corpus, and the load generator's own bootstrap adds to that corpus on every
   run -- `plan_nodes` reached 907825 rows and `plan_segments` 748331 when this
   was measured. A covering index took one search from 1366 to 130-210
   milliseconds, which held for a few windows and then degraded again as the
   corpus grew. Measured with the index in place: 56 searches a second, each
   fetching about 20000 segment rows, which is 1.1 million tuples a second from
   the plan tables alone.
2. [x] The remaining shard migration is cancelled. The resident environment
   keeps only what a run needs: the accumulated data was deleted and the release
   redeployed onto empty schemas, which is what the owner asked for rather than
   copying 176 GB of history. Trip Planning and Seat Assignment had already been
   cut over to the read shard and stay there. What this closes is the reason the
   old rows existed at all -- legacy holds without supply facts, 58 un-funded
   refund cases, 150530 APPROVED refunds and 133034 APPROVED changes from the
   broken era -- so those items are gone with the data rather than repaired.

3. [ ] Cut the remaining databases over to their shards, if the split is wanted
   later. The chart defines six
   shards, but only six of the thirty-seven services set `pgInstance`; the other
   thirty-one use `core`, including every read-heavy one, and the read shard's
   copies of `trip_planning`, `offer_management`, `capacity_availability` and
   `seat_assignment` hold no tables at all. Core is doing the whole system's
   work: measured over 25 seconds it served 20886 fare-pricing transactions and
   12304 trip-planning ones, and 45.4 million trip-planning tuple fetches. The
   loads that need moving are the read shard's seven databases first, then the
   transactional shard's five; `reporting` is the worked example of the copy,
   verify and switch that this needs. Plan for the volume before starting it:
   measured on `core`, `fare_pricing` is 42 GB, `offer_management`,
   `capacity_availability` and `seat_assignment` are 43 GB each, and
   `trip_planning` is 3.4 GB, so the read shard's seven databases come to about
   176 GB of data that has to move while nothing is writing to it.
3. [ ] Bound the write amplification before moving that data, because a copy
   carries the growth with it. `capacity_supply_observations` holds 503452 rows
   in 25 GB -- about 50 KB per admission -- and `seat_maps` holds 427934 rows in
   37 GB. Both are written per admission or per change and keep every version,
   so the shards would fill again at the same rate. Decide what a useful
   retention for each of them is, the way the outbox and `processed_events`
   sweeps already do.
4. [x] Provider Integration left a share of reservations unanswered. The adapter
   was returning the stored reservation without publishing anything when it was
   asked again, which is the one case where the answer is the whole point;
   publishing the confirmation again raised the answered share from 437 of 1601
   to **1948 of 2949** in a minute, and Booking's own confirmations from 603 to
   2459. Measured after both this and the duplicate publisher below landed, the
   provider leg is no longer the reservation's tail: at the ninety-fifth
   percentile it answers in 0.09 to 0.17 seconds in the minutes where capacity
   is keeping up, and a reservation request reaches its provider confirmation in
   0.04 to 0.12 seconds.
5. [x] Every replica published the outbox. The relay publishes before it marks
   rows published, so two replicas that selected the same unpublished rows both
   published them, and the share was the relay's work time over its poll
   interval. Measured at revision 399: **38 percent** of
   `events:booking-orchestration`, **40 percent** of `events:journey-order`,
   **43 percent** of `events:post-sales` and **74 percent** of
   `events:capacity-availability` were second copies that every consumer group
   read, parsed, deduplicated and acknowledged. A session advisory lock elects
   one publisher per database in java-kit and rust-kit; measured at revision
   401, all four streams carry **0 percent**. The Go kit still relays from every
   replica, which its 250 millisecond interval bounds at the 2 to 4 percent
   measured on `events:provider-integration` and `events:seat-assignment`; it
   needs a pool handle to hold a session, which its `DBTX` interface does not
   provide.
6. [ ] Capacity's admission serialises on one advisory lock per inventory pool,
   and that is the constraint that now fails the reservation poll. Measured at
   revision 401, in the minutes the hold was slow: 21 of the 24 active sessions
   on `capacity_availability` were waiting on
   `pg_advisory_xact_lock(hashtextextended(...))`, waits of 1.0 to 2.9 seconds
   were logged as slow statements, and a reservation reached its capacity Hold
   **85 to 112 seconds** after it was requested at the ninety-fifth percentile.
   The lock is held for the whole admission transaction, and load generators
   reuse a small set of `pool:segment:class` keys, so admissions into one pool
   queue behind each other. The next measurement is where the hold time goes:
   the supply history read, the pool aggregate update, or the hold insert, in
   that transaction. Splitting the lock by unit, or shortening the transaction,
   is what the number asks for.
7. [ ] Disruption must not report a business conflict as an availability
   failure. Selecting an option for an order that already has a refund or change
   in progress answers 503 `UNAVAILABLE`; a conflict has its own status, and the
   flow has to decide what the honest terminal outcome for that case is.
8. [ ] A post-sales case waits forever for a capacity hold its cancelled booking
   never released, and every later journey that reuses that order fails on it.
   Found rather than fixed; the evidence is complete.

   At revision 418 the window carries 911 refunds and 602 changes that failed at
   `case-status`, and 191 distinct case ids account for 1639 of those failures --
   8.6 journeys per case in ten minutes, so the client is re-driving the same
   orders. Every one of those cases is `EXECUTING`, most since 01:24 to 04:12,
   and one of them is worth following: `01a0dbe9-cb3b-7fa7-a645-3bb80a294b17`
   has done the whole change -- target, replacement reservation confirmed,
   difference payment captured, old entitlement voided, new ticket issued -- and
   stops on the last check in `ChangeExecutionService.advanceLocked`:

       for (String bookingId : change.scope().orderItemRefs()) {
           if (!"CANCELLED".equals(booking.status())) return change;
           if (!"RELEASED".equals(hold.status()))     return change;   // here

   Its original booking `sb-01a0dbe8-1a3f-7220-863e-8ef1b2c4cf60` is CANCELLED
   with `cancellationReason: CHANGE`, and its hold
   `hold-01a0dbe8-1a9a-7831-97db-eb234bb583ef` is still Confirmed. That reason is
   the one `ChangeExecutionService` sends when it voids the old entitlement, so
   the path is `handleEntitlementVoided` in booking-orchestration -- which cancels
   the booking and publishes `SegmentBookingCancelled` -- and capacity releases by
   segment booking reference when it consumes that event.

   Capacity has no record of it: there is no `pending_booking_releases` row for
   the booking (and none of the 292 pending rows belongs to a booking whose hold
   exists, so that mechanism is working), the dead letter holds no entry for it
   either, and the hold is neither Released nor Expired. The dead letter does
   hold 99 `SegmentBookingCancelled` entries from the same period, so more than
   one way to lose a release is in play.

   Reading `try_handle_inbound_segment_booking_release`, a release that reaches
   the handler has three ways to end without releasing: the event id is already
   in `processed_events` (the handler rolls back and returns Ok), the hold is not
   found by `references.segmentBookingRef` (it is parked in
   `pending_booking_releases`), or the hold is found and live and the release then
   fails on `reservation_facts_from_hold` -- which requires the hold to carry
   `supplyEvidence` -- or on `prepare_supply_history`, before it ever touches the
   pool. A failure there leaves the event to be retried and then dead-lettered,
   which is the 99. Which of the three this booking took needs one more
   measurement, and it is cheap: the release handler's own failure line names the
   reason.

   The wait has no deadline. The same class carries `RESERVATION_WAIT_LIMIT` and
   fails a case whose replacement reservation does not confirm in fifteen
   minutes, but nothing bounds the release wait, so a case whose hold is lost
   stays EXECUTING for hours and every journey that touches its order fails with
   `unfulfilled` rather than ending.

   **Repaired, and the diagnosis proved by the repair.** Calling
   `POST /api/v1/capacity-holds/{id}/release` on the stuck hold released it, and
   the case -- which had been EXECUTING for five and a half hours -- reached
   APPLIED six seconds later. The same call against every other blocked case: 34
   holds belonging to cancelled bookings were still Confirmed, all 34 released,
   and 34 of the 38 cases went APPLIED. The window after moves `change
   case-status` from 602 failures to 441.

   What that proves is that the hold is the only blocker and that the release
   path works when it is asked by hold id. What it does not fix is the road the
   release took: capacity's event handler is still the one that lost it, so the
   leak can happen again, and the repair was a person with a curl equivalent.

   The plan, in this order:

   1. Instrument the release handler with the reason it gave up -- the three
      paths above are one line each -- and read it for this booking. That decides
      whether the leak is a fencing gap, a lookup gap or a supply-evidence gap,
      and each of those is a different fix.
   2. Bound the wait in `ChangeExecutionService` and, on expiry, finish the case
      rather than fail it. The work is done: the replacement is confirmed, the
      difference is captured, the old entitlement is voided and the new ticket is
      issued. What is missing is capacity's bookkeeping, so the honest outcome
      for the user is the change they asked for, with the un-released hold
      recorded as a compensation mismatch -- the table for exactly this anomaly
      already exists -- for capacity to reconcile. Failing the case would tell
      the user their change did not happen when it did.
   3. Then decide what a dead-lettered release should do. Silently holding a seat
      is the worse of the two failures, so a release that cannot be applied
      should be parked rather than dropped, and a sweep should apply what is
      parked.

   **The change half is done; the refund half is the same shape and is not yet
   fixed.** The window at revision 419 carries 223 failed journeys of 75834 --
   0.29 percent, against 4208 of 66765 two revisions earlier -- and the largest
   group in it is 127 refunds that failed at `case-status`, every one of them the
   same case: `psc-01a0db50-09b7-7f80-ac0e-15feca7d2f24`, EXECUTING since 01:24.
   Its execution plan is untouched -- DECISION_CONFIRMED through APPLY_RESULT all
   still PLANNED, `refundPayments` empty -- and its order item's hold was released
   in the repair above, so the refund flow is held before its first step by
   something else.

   A refund is not advanced by an executor the way a change is. It is advanced by
   `PostSalesExecutionReconciliationService.reconcile`, which reads the owning
   services' state -- payment's refund status, ticketing's void, booking's
   cancellation, capacity's release -- and records the steps that state proves
   (`PostSalesExecutionEvidenceReader`, whose comment is that it reads without
   issuing any downstream command). Its only caller is
   `PostSalesExecutionReconciliationController`, an HTTP entry point. So the
   question is who calls that endpoint and why it was never called for this case
   while it was for the twenty-seven thousand refunds that reached APPLIED. Note
   that the first step it would record is DECISION_CONFIRMED: nothing has read
   this case at all, which is a different failure from a read that came back
   empty.

   Answered in part: nothing else in post-sales calls it. Its only caller is its
   own controller, the load generator does not call it -- `journey_refund.go`
   posts evaluate, target-offer and approve -- and no event handler hooks it. So
   whatever advances a refund to APPLIED is on the approve side, in
   `PostSalesApplicationService` (`isRefundType` around line 401, the refund case
   creation around line 604).

   Followed one step further: nothing calls the reconcile endpoint at all. A
   search for `reconcile-execution` across every service and the load generator --
   Go, Java, TypeScript and Python, tests excluded -- finds one hit, the mapping
   on the controller that declares it. `reconcileExecution` has one caller
   (`PostSalesExecutionReconciliationService.reconcile`), which has one caller
   (that controller), and `PostSalesEventHandler` neither records steps nor
   applies results. So a refund that reaches APPLIED does it through
   `approve` -> `startApprovedExecution` -> `redriveApprovedExecution`, and the
   redrive path is what to read next: `prepareExecutionRedrive` and
   `executionRedriveEvents` in `PostSalesCase` decide whether the redrive does
   anything, and `redriveApprovedExecution` returns without publishing when
   `prepareExecutionRedrive` says there is nothing to redo. That is the shape to
   check against this case, whose every step is still PLANNED.

   A database check rules the redrive out as the difference: of the refund cases,
   28556 APPLIED, 4 EXECUTING and 2 QUOTED all carry `executionRedrive`, so a
   redrive was prepared for the stuck ones as well.

   What completes a refund is a `CapacityReleased` event. `PostSalesEventHandler`
   handles exactly that type by calling `applyForSegmentBooking`, which finds the
   cases still waiting on the segment booking, and for an EXECUTING case that is
   not a CHANGE calls `recordCapacityReleased` and then `applyResult("all scoped
   capacity released")` -- APPLIED. So a refund's terminal transition is driven by
   the same event family as the hold leak: capacity releases, and the release is
   what tells post-sales the refund is done.

   That is where this case's answer lies, and it is now a short question: the
   repair above released its hold, which is what emits `CapacityReleased`, and the
   case did not move. Either that release's event did not reach the handler (the
   trimmed-stream shape again, which would make both halves one defect), or the
   handler saw it and `recordCapacityReleased` did not accept it. Read those two
   next: whether capacity's release-by-hold-id emits the event with the segment
   booking reference the handler matches on, and what `recordCapacityReleased`
   requires of the case.

   Read: the case has not been written since 01:24 -- `releasedOrderItems` is
   still empty and `updated_at` is the opening time -- so even the release made
   during the repair did not touch it. `findPendingCapacityRelease` is therefore
   as much a candidate as a lost event: a `CapacityReleased` that arrives and
   matches no pending case leaves no trace at all. Read the query and the
   envelope's reference fields together, and check the two against each other
   before changing either.

   Read together, and the matching side is not the fault. `CapacityReleased`
   carries `references.segmentBookingRef`, and `findPendingCapacityRelease`
   matches a case whose `scope.orderItemRefs` contains that booking, whose
   `releasedOrderItems` does not, and whose status is APPROVED or EXECUTING --
   which is exactly this case: it is EXECUTING, its order item refs contain
   `sb-01a0db4d-c07f-7287-a72f-fbd3f9496c2d`, and its `releasedOrderItems` is
   empty. Had the event arrived, the case would have been found and applied. So
   the event is what is missing, and one check remains: whether the
   release-by-hold-id path used in the repair emits `CapacityReleased` at all,
   or releases silently. If it releases silently, that is the last piece and the
   fix is the one already planned -- capacity must not lose or silence a release.
   The first look points at silence: `release_hold_with_reason` in the storage
   adapter mutates the pool and returns, and a scan of that method for the outbox
   append, `domain_events_to_wire` or a `CapacityReleased` envelope finds none.
   Confirm by reading the whole method and its caller before changing it: if the
   API release path never publishes, then every repair made this way released the
   seat without telling anyone, which is exactly why the refund case sat untouched.

   **Then this one, so that it is not mistaken for the same thing.** With the change leak repaired, the window's largest group is
   `refund-status`: 1025 refunds and 132 disruption refunds that did not reach
   SETTLED inside the client's poll. The example checked
   (`psc-01a0dd10-b5a9-7fbb-8c57-ccc248b60ab5`) is APPLIED, so those are late
   rather than stuck -- the refund completes, just after the client has stopped
   asking. That window also ran at P99 1924 milliseconds against 129 in the one
   before it, so measure the refund path's own latency before attributing
   anything to a lost release.
9. [x] Offers answered with an unexpected status. The window at revision 417
   carried 592 purchase, 258 change and 330 transfer jobs that failed at `offer`
   with a 422: `Fare Pricing quote has not been consumed` or `No consumed Trip
   Planning itinerary found`, both from `offer-management`'s build of its command
   from consumed upstream state. The client does retry -- one transfer journey
   retried ten times over thirty-three seconds -- so the upstream state was
   arriving late rather than never: `events:trip-planning` produced 150 entries a
   second while this group consumed 78 to 115, and its lag grew from 258 to 1461
   entries in twenty seconds, which put one itinerary 73 seconds behind the
   offer that needed it. The database and the CPU were idle; the concurrency was
   the constraint. Twelve handlers per consumer and a fourth replica at revision
   418 leave the group at a lag of 0 to 4, and the next window carries no `offer`
   failure at all.

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
