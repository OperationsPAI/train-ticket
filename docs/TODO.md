# TODO

Updated: 2026-09-28, Asia/Beijing.

This is the single active backlog for the resident release. It records what is
still open, not what has been done: implemented and deployed behaviour lives in
[current status](00-current-status.md), and the telemetry contract lives in
[business metrics](07-observability/business-metrics.md).

An unchecked item is a confirmed gap or an unverified area. An unverified area
is not an assertion that the behaviour is wrong.

## Acceptance

### T01: Prove a clean resident cycle

Status: **closed for the steady resident cycle**, with one named exception
below. The whole resident cycle is clean on the current revision.

1. [x] Keep one image set for a whole 600 second resident cycle and retain every
   request and journey record, including the recorder's drop counter. Revision
   452 ran `23:33:00` to `23:43:00` with no deployment inside it; the recorder
   read `written=1427213 dropped=0` over the pod's life.
2. [x] Require zero unexpected HTTP errors, zero failed expected business
   journeys and no recorder drops, without shortening the workload or extending
   the client timeout. The window carries 417464 requests and 46350 journeys at
   P99 294 milliseconds with **0 unexpected error groups and 0 failed
   journeys**. The retry 422s the generator declares expected are counted
   separately: 1398 of them, 0 unexpected.
3. [x] Record the same window's consumer lag, pending entries and dead letters,
   and the money, capacity and wallet conservation checks. Every dead-letter
   stream is empty; payment's conservation identity holds exactly at the window
   (captured 5408457406 = refunded 1187431372 + unsettled 4221026034 + reversed
   0); wallet's gauge states are internally consistent (available 117100135 +
   redeemed 741300 = issued) and agree with the durable issuance and redemption
   totals to within the gauge's refresh interval.
4. [x] Attribute any failure that does appear to an operation and an event
   timing rather than to a service name. The failures this pass found are all
   attributed that way in
   [current status](00-current-status.md#the-reservation-poll-stopped-failing-and-what-was-left-behind-it).

The exception is a burst above the steady envelope. A window that begins when
the load generator restarts carries its catch-up, and the envelope itself
multiplies `target_rps` by a diurnal peak of `4.0` and a burst of up to `8.0`,
so minutes of **15000 to 21500** journeys appear -- three to six times the
largest steady minute these cycles reach. Those minutes fail on the offer step,
on the reservation poll or on risk, and it is a real failure for that user.

The measured reason is in
[current status](00-current-status.md#the-ceiling-under-all-of-it-is-one-redis-thread):
Redis is single-threaded and sits at a whole core through those minutes while
every consumer group's own lag reads zero. What this pass did to it, all measured:

| | Before | After |
|---|---|---|
| commands a second | 21376 | about 11700 |
| Redis clients | 1900 | about 1260 |
| subscriptions | 149 | 122 |
| `xpending` a second | 2028 | under 100 |
| `xautoclaim` a second | 4896 | 250 |
| duplicate entries on the three relay-affected streams | 44 to 66 percent | 0 of 28034 sampled |

What is left, in the order worth trying -- **all four deferred by the operator's
instruction**, which fixed the traffic level and put further bus throughput work
out of scope for this pass. They are the order worth trying if that decision is
revisited; at the fixed level the windows carry no failed journey and no
unexpected error, which is the acceptance boundary this pass is judged by.

1. [ ] **Micro-batch the read.** `xack` is now the largest class (5447 a second)
   and its count is the number of delivery rounds, not entries: a reader that is
   keeping up gets one to three entries per poll, so one XACK per batch is one
   XACK per entry. Reading on a short tick (50 to 100 milliseconds) so entries
   accumulate, instead of returning the moment one is available, multiplies the
   batch size by ten and divides both `xreadgroup` and `xack` by it. It costs up
   to a tick of delivery latency, which is well inside the ten second poll.
2. [ ] Reduce the groups per stream further. Measured now: ten groups read
   `events:booking-orchestration`, five read `events:capacity-availability`, and
   `reporting` still holds fourteen of the 122 subscriptions.
3. [ ] Or give the bus more than one thread: shard streams across Redis
   instances, or move the reads to a transport with parallel work. This is the
   only step that removes the ceiling rather than raising it, and it is a change
   to the deployment's shape rather than to a service.
4. [ ] Decide whether the acceptance envelope includes a burst the steady cycles
   never reach. If it does, the arithmetic in
   [current status](00-current-status.md#the-configured-envelope-is-wider-than-any-window-has-measured)
   says the system must be sized for 640 journeys a second, and the largest
   observed is 358.

At the largest bursts the first service to miss its window is now
`risk-compliance`: the `04:15` minute on 2026-09-27 carried 26326 journeys and
6551 failed purchases at `risk-status`. That is the same Redis queueing seen from
the request side rather than from the consumer side, so it moves with the bus.

### T19: Stop the generator re-driving resources that are already finished

Status: **closed**. Measured after the fix: a ten minute window over one whole
diurnal cycle at the level in `values.yaml` carries 274892 requests and 31865
journeys with **0 failed journeys and 0 unexpected HTTP errors**.

What it was, and what each fix was. Every failure in that class was a 422 the
service is right to send, and all of them came from re-driving something the
services had already finished:

| Group | Window count | Cause | Fix |
|---|---|---|---|
| `refund:refund-case` | 828 from **two** purchases | the order already carried an applied refund, cancellation or change, and the purchase sat in `post_sales_pending` being asked again more than four hundred times | a create answered `ORDER_NOT_REFUNDABLE` is terminal: the purchase is retired in the state the journey wanted |
| `change:change-target` | 2079 from **three** cases | the case already held a target offer, and replacing it is the `/target-offer/revise` path | the resume path reads the case and adopts what it holds |
| `insurance:anc-order` | 625 | the order no longer carried the segment the purchase was registered against, and `insurance_pending` kept it in the pool | the request is finished and the purchase retired |
| `fulfillment:boarding` | 496 | the entitlement was voided by a refund, or the ticket was already boarded | both are terminal, and the journey finishes instead of failing. The check reads the parsed response body: the logged detail truncates it at 150 characters behind the correlationId |
| `disruption:disruption-select` | 446 from **two** cases | the recovery option was refused because another journey had closed the order | same shape as the refund |

Every post-sales, recovery, ancillary and insurance request now counts its failed
attempts and retires its purchase after five, the bound the platform puts on its
own handler retries.

The reservation path's tail was closed one hop at a time over 2026-09-27, one
commit each, and each hop is recorded with its measurement in
[current status](00-current-status.md#the-reservation-poll-stopped-failing-and-what-was-left-behind-it):
`provider-integration`'s reader loops, `finance-settlement`'s shared handler
pool, `transfer-management`'s one-reader-for-three-streams, the four threads
`post-sales` gave the capacity stream, `invoicing`'s workers on the two streams
an amount basis reads against, and the replica counts the eight-times burst
asked for across `booking-orchestration`, `payment`, `offer-management`,
`journey-order`, `traveler-profile`, `fare-pricing`, `identity-verification` and
`finance-settlement`. The standing policy and the reservation supply facts stay
deployed.

The list below is the earlier pass's, kept because each item is still the
reason its area fails when it does; some of it was overtaken by the reset and
the shard cutovers, and the raised bursts it describes are now clean. The
history of what each one cost is in
[current status](00-current-status.md#the-resident-cycle-is-not-yet-clean):

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
   401, all four streams carry **0 percent**. The Go kit could not take the lock
   -- its `DBTX` interface offers no session to hold one -- and it was left
   relaying from every replica behind a note that the damage was "2 to 4
   percent". Measured on 2026-09-27, it was 999 of 1500 entries on
   `events:provider-integration` and 993 of 1500 on `events:seat-assignment`, and
   `events:payment` was 885 of 2000 because payment built its own relay too.
   **Closed**: the Go relay takes the lock on a connection of its own from the
   `*pgxpool.Pool` every Go service already passes, payment uses the kit's relay,
   and 0 of 28034 sampled entries across all 37 streams are duplicates.
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
7. [x] Disruption no longer reports a business conflict as an availability
   failure. `DownstreamError` carries the downstream's own HTTP status, and the
   handler maps a client error to the contract's vocabulary -- 409 `CONFLICT`,
   412 `PRECONDITION_FAILED`, 422 `DOMAIN_RULE_VIOLATION` -- keeping 503 for a
   connection failure or a server error. Selecting an option for an order that
   already has a refund or change in progress therefore answers its own conflict
   rather than an outage, and the reason is logged with the path and the code.
8. [x] A post-sales case no longer waits forever for a hold its cancelled
   booking never released. `ChangeExecutionService` records the wait and, after
   `RELEASE_WAIT_LIMIT` (two minutes), releases the hold itself, because the
   release capacity would have acted on can be lost to stream trimming or to a
   dead letter nothing replays -- 99 `SegmentBookingCancelled` entries sat in
   `events:capacity-availability:dlq` when this was measured. The limit is
   asserted directly. Verified live at revision 504: 400334 cases APPLIED, 537
   FAILED, and **0 EXECUTING older than thirty minutes**, against the 34 stuck
   holds and the one five-and-a-half-hour case this was filed from.

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
   Corrected, and this note is worth keeping because the first reading was wrong:
   the path does publish. `release_hold` calls `release_hold_with_reason`, which
   calls `mutate_hold_pool`; that method runs the mutation, turns its events into
   envelopes with `domain_events_to_wire`, appends each one with
   `OutboxAppender::append`, and commits. A scan of only
   `release_hold_with_reason` suggested silence because the publishing is one
   level down.

   So the release at 09:32 did emit `CapacityReleased`, at a time the streams were
   healthy, and the case was still untouched. The remaining candidates are now on
   the post-sales side of the handler -- and that side is now clear of every
   condition but delivery. Its filter accepts `CapacityReleased` (the handler's
   own allow list has it), `findPendingCapacityRelease` matches this case, and
   `recordCapacityReleased` does nothing but check the order item is in scope,
   add it to `releasedOrderItems` and return whether every scoped item is now
   released, which for a one-item case is immediately true. So all the handler
   needed was the event.

   Delivery is where to look, and there is a number for it: post-sales' consumer
   group on `events:capacity-availability` is behind right now -- measured lag 360
   entries, against 11 for booking-orchestration, 9 for reporting and 21 for seat
   assignment on the same stream (fare-pricing is the only group further behind,
   at 1049). Post-sales is the group that has to consume `CapacityReleased`, so a
   Measured, and post-sales is not the one that is stuck: over twenty five seconds
   the stream produced 115 entries a second, post-sales read 134 and its lag fell
   from 504 to 4, so it drains a backlog rather than accumulating one. The group
   that is falling behind on this stream is **fare-pricing**: 95.4 entries a
   second read against 115 produced, lag growing 2993 -> 3476. That is the
   consumer of the combined availability snapshot capacity publishes on every
   admission, and it is a finding in its own right -- but it is not this case,
   whose release was published hours before that lag existed.

   **Why the case sat untouched, and it is a race rather than a loss.** Its own
   hold is a different one from the change case's above:
   `hold-01a0db4d-c0ae-7c90-ac58-39b9e9b06c75`, for booking
   `sb-01a0db4d-c07f-7287-a72f-fbd3f9496c2d`, both from the case's scope. It is
   Released -- and was released at **01:24:11**, while the case opened at
   **01:24:21**, ten seconds later.

   So the `CapacityReleased` this case is waiting for was already published and
   consumed before the case existed. `applyForSegmentBooking` looks for cases that
   are already waiting on that booking; on that day there was none, the handler
   acked, and when the case opened ten seconds later nothing would ever mention
   the release again. The repair in the same minute changed nothing because the
   hold was already Released, so capacity's release path had nothing to emit. The
   twenty-seven thousand refunds that reached APPLIED are the ones whose release
   arrived *after* their case opened.

   **The fix is the reconciliation this service already has and never calls.**
   `PostSalesExecutionReconciliationService.reconcile` reads the owning services'
   current state and applies what it proves -- which is exactly what a case needs
   at the moment it starts executing, because the state it depends on may already
   be there. Call it from `startApprovedExecution` (and from the redrive path)
   once, after the case is EXECUTING: a refund whose capacity is already released
   then completes at once instead of waiting for an event that has been and gone.
   Note that this is not a workaround for a lost event -- it is the read side of
   the same state the events describe, and it is what makes the flow tolerate
   events that precede it.

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
10. [x] A refusal the journey handles is not the journey's failure. Measured at
   revisions 483 to 486: **every load generator restart** left two fulfillments
   failed at the boarding step and one change failed at `change-execute`, all
   with answers the journey already treats as terminal. The journey handled each
   one correctly -- a diagnosis log proved the matcher fired -- and the attempt
   layer failed it anyway: the client records every unexpected status for the
   attempt, and a journey that returns a clean outcome still has that recording
   named as its failure. The handled refusals are now declared expected at the
   request (the boarding and no-show 422s, the refund create's 409 and 422, and
   the disruption select's 409), which keeps them out of the attempt's failure
   list while still returning the status to the branch that decides on it. The
   change half was a real 500: post-sales wrapped entitlement-ticketing's 412
   "entitlement is already voided" in an `IllegalStateException`, so a change
   whose void had in fact been applied was answered as a server fault. It now
   records the command as satisfied. Measured after both: a restarted generator
   reports `errors {}` in its first stats cycle where it used to report three
   failures, and the revision 488 window carries 0 failed journeys.

### T20: The two dead-letter sources are both a race, and both are fixed

Status: **closed**, revision 479 (the same images as 478). Both were produced
during the flat high-rate sweep on 2026-09-27, neither recurred under the
acceptance cycles before them, and both have a measured root cause rather than a
reclassification.

1. [x] `events:capacity-availability:dlq` held six `CapacityHeld` entries for
   `booking-orchestration`, `attempts=1`,
   `failureReason=HandlerResult.FATAL_FAILURE`, dead-lettered at `06:47:07`. The
   six booking snapshots are the answer: each one is `CANCELLED` with
   `cancellationReason: SAGA_STEP_TIMED_OUT` and a `pendingProviderReference`
   but no `capacityHoldId`. That is the race -- the saga's reservation step timed
   out and cancelled the booking while the provider's confirmation was still
   waiting for its Hold, and the Hold arrived a moment later.
   `markCapacityHolding` completed the deferred confirmation, and
   `confirmReservation` refused a `CANCELLED` booking with an
   `IllegalStateException`, which `handleUpstreamEvent` classifies as fatal. The
   hold is still real and its reference is still worth keeping -- the release
   path needs it -- but it must not move the booking out of `CANCELLED`:
   `SegmentBooking.completeDeferredProviderConfirmation` now takes the same route
   `confirmFromProvider` takes for a terminal booking and asks the provider to
   cancel the reservation. `handleCapacityHeld` also stops throwing on a segment
   booking with no owning saga: that is an ack-skip beside the unknown booking
   reference it already skips.
2. [x] `events:finance-settlement:dlq` held five
   `RevenueRecognitionReversed` entries for `invoicing`,
   `failureReason=MaxDeliveryAttempts`, `attempts=5`, dead-lettered at
   `05:30:51`. It was not a long wait: the reversal was projected before the
   recognition it names, because the projection runs eight workers on the
   finance stream and the two events of one refund do not have to be applied in
   order. All five orders carry the reversal in their amount history now, which
   is what a redelivery applied after the dead letter. The reversal is now kept
   in the order's durable history until the recognition arrives, and the
   recognition's own delivery applies it with the same ceiling and balance
   checks the inline path uses.

The measured effect is in
[current status](00-current-status.md#the-two-dead-letter-sources-and-their-root-cause).

## Open verification

### T03: Finish the coordinated supply integration

Status: **closed**, revision 515. General multistop correctness is proven against
the release's own recorded facts, and the two fences the items ask for are
proven at the service, not only in the aggregate.

1. [x] Resolve accepted class, service, date, original purchase source,
   interval and composition for every active historical Hold; reject ambiguous
   source facts instead of guessing. Measured over the whole store: of
   **1,410,196** holds, **0** live holds lack a capacity unit or a resolved
   service in `supplyEvidence`, **0** carry an invalid interval, and **0** carry
   a confirmation without its instant. The refusal is live too: a class whose
   published map selection is missing or ambiguous answers **412
   `published SeatMap selection is missing or ambiguous`** rather than choosing
   between them, and `multistop_supply_live` accepts only that refusal.
2. [x] Define and enforce the binding between a Hold and its allocated physical
   unit, including historical references and composition replacement mappings.
   Every live hold names its unit and pool, the fold binds an allocation to its
   hold field by field (segment booking, order, traveler, unit, interval) and
   refuses a mismatch, and `resolve_allocation_unit` places a unit recorded
   against an earlier composition through the published replacement mappings.
   Measured: no unit carries two live holds.
3. [x] Derive availability and `CapacitySnapshotUpdated` from the same combined
   Hold and allocation occupancy across related pools and overlapping intervals.
   The fold counts exactly the allocations that reach into the requested
   interval -- measured live on SECOND_CLASS, FIRST_CLASS and SLEEPER_HARD: the
   units occupied in an interval are the allocations that overlap it -- and a
   seat sold for stops 0-1 is still sellable for stops 1-2 while a seat sold
   across 0-2 is free in neither.
4. [x] Fence expired or released Holds against late confirmation or allocation,
   and include unfinished allocation attempts in occupancy accounting. Stated
   live: confirming a released hold answers **412 `Hold is not in a state that
   can be mutated`** and the hold stays RELEASED; allocating against a released
   hold answers **412 `capacity Hold is released`**, from the durable
   `capacity_hold_fences` row the release writes (459,526 of them). Measured over
   the whole store: **0** holds were confirmed after being released. A Hold that
   is held but not yet allocated still occupies its unit in the fold.
5. [x] Verify overlap, nonoverlap, physical classes, standing, composition
   changes, replay and concurrent release against recorded source facts. The
   crate's suites pass (80 tests: aggregate, replay, standing, composition,
   warm views, API), `multistop_supply_live` adds the live multistop case above,
   and the live store carries **0** units with two live holds in the same
   interval and **0** repeated-interval duplicates.

   Not run, and named so it is not read as done: the live suites that need
   captured fixtures rather than a URL -- `supply_hold_live`,
   `hold_references`, `concurrent_inventory`, `booking_release_ordering`,
   `standing_supply_live` and the fixture-based half of `recorded_supply`. Each
   needs a JSON capture of a real booking, hold or event pair from a run; the
   environment's streams no longer hold them. What they would add over the
   measurements above is a replay of one specific historical hold, which item 4
   and item 1 already cover in aggregate.

Two stop intervals and the deployed standing policy do not prove general
multistop admission. See [capacity design](10-domain-enrichment/capacity-overbooking.md)
and [standing policy](08-contracts/standing-capacity-policy.md).

### T04: Verify automatic waitlist fulfillment

Status: partly verified. Matching, pricing, offer creation, order creation,
offer expiry and the guarantee guard are exercised by the resident workload now
that three defects in this path are closed (items 3 to 5); a successful
confirmation is not, and the reason is measured to the seat in item 6.

1. [x] Match released capacity by date, class, interval and traveler intent. A
   credit carries the segment, scheduled service, departure date, seat class,
   interval, stop-sequence version and intent fingerprint, and the durable claim
   matches all of them in one query under `FOR UPDATE OF e SKIP LOCKED`; the
   resident workload ran 9880 `WaitlistMatchStarted` events in the window this
   was read from, each of which required a credit to match its entry.
2. [x] **A promotion that fails does not immediately take the same credit
   again.** This was a defect, not a verification: a promotion whose order ended
   terminal returned its credit to the pool and the entry to the queue, and the
   one-second promotion tick handed that credit straight back to the same entry.
   Measured: `WaitlistMatchStarted` ran at **215 to 310 a minute** between 12:29
   and 12:46, with an equal number of requeues -- nearly every promotion undone.
   A queued entry now carries `promotionRetryAfter`, set five minutes ahead when
   its promotion is undone. Measured after: **1 to 6 a minute** from 12:47 on,
   and the entries sit at `QUEUED` with the retry instant set for the whole
   backoff. The credit still reaches every other waiter immediately.
3. [x] **The promotion re-quotes with the guarantee's own pricing inputs.** This
   was a defect: the promotion priced the seat from whatever the request
   carried, the request carried a product code, a channel and a class and no
   distance, and fare-pricing fell back to the flat base fare -- measured
   directly against the deployed service, 28750 without the distance against
   16979 with it for the same segment and class. The promoted offer therefore
   exceeded the amount the customer had authorized and every entry was
   suspended: 25 of 25 in one ten minute window, and no entry had ever reached
   `FULFILLED`. The entry now carries the inputs its own quote was priced from
   (product code, channel, distance, departure time) and the promotion sends
   them.
4. [x] **The pricing context survives the database round trip.** This was the
   second half of the same defect, and the reason the fix above looked inert on
   the live release: `entryFromRow` rebuilt the aggregate field by field and
   named neither `pricingContext` nor `promotionRetryAfter`, so an entry was
   written with its distance and read back without it. Measured on a live row,
   `distanceKm` was present at insert and gone on the next reload, and 25 of 25
   entries in the window read back as the flat-fare default. Both fields now
   round-trip; measured after the fix, 17 of 17 new entries keep the distance.
5. [x] **The offer is built on the channel the entry arrived on.** Also a
   defect: the promotion re-quoted on the entry's channel but created the offer
   on the client's configured one, and offer-management refuses an offer whose
   channel differs from its quote's with a 422 -- the status the promotion
   treats as "not ready yet" and retries every second. Measured live, the
   mobile-channel quote was refused with `FARE_QUOTE_MISMATCH` on every tick and
   the promotion failure counter ran at **6 a second** while `offer_created` and
   `order_created` stayed at zero across the whole resident population. The
   offer now carries the entry's own channel. Measured after: `failed` at about
   1.5 a minute, and the first 49 offers and 49 orders this environment has ever
   produced from a promotion.
6. [x] A successful confirmation: payment guarantee captured, offer window
   honoured, downstream ticket issued, and exactly one capacity and payment
   effect after a restart. **Still blocked, and the reason is now measured to
   the seat.** With the three defects above closed the promotion reaches the
   booking saga for the first time -- 49 offers and 49 orders in ten minutes,
   where the release had produced none -- and every order fails with
   `NO_AVAILABLE_CAPACITY`. That is a real capacity outcome, not a defect:
   queried live, the class the entry waited for is genuinely sold out
   (`remainingByClass` for the segment's `BUSINESS_CLASS` reads
   `total 36, available 0` while first and second class hold hundreds), and the
   release the credit was taken from is re-sold before the saga's reservation
   lands. A waitlist whose pool never has a free unit cannot fulfil, which is
   what the workload's demand on that class produces.
   The 45 minute window measured earlier, on the pre-fix release, also showed
  172 of 211 orders refused by risk-compliance's velocity rules
  (`VELOCITY_TRAVELER_BOOKING`, more than two bookings per traveler per hour,
  and `VELOCITY_ACCOUNT_ORDER`, more than three orders per account per five
  minutes). The rules are the platform's own anti-abuse policy and the workload
  reuses travelers (`p_new_traveler: 0.10`), so a waitlisted customer here is
  almost always the same person who just booked; whether a promotion *should*
  be exempt from the velocity rules stays a policy decision rather than a
  defect this pass found. Verifying this item needs a promotion for a traveler
  with no recent bookings into a pool with a free unit, which this workload does
  not reliably produce.

   Recipe for whoever takes it, from what the release has already shown. The
   credit a promotion consumes is a `CapacityReleased` on the entry's own
   segment, service, date, class and interval, so the fixture has to *make* one:
   buy more than one seat in that class for that date through the normal funnel,
   then cancel or refund them together. The promotion takes one credit, and the
   extra released units are what its reservation lands on -- which is exactly the
   step the resident population cannot win, because it is competing for the same
   single released seat it just produced. Two smaller preconditions are worth
   clearing at the same time: the traveler should have no recent bookings, so
   risk-compliance's velocity rules do not refuse the promotion order, and the
   entry should name a pool whose class has slack outside the entry's interval,
   so `remainingByClass` is not zero for the whole train.

   **Taken, and the item is closed at revision 520 with a live confirmation.**
   The recipe's own answer is that the winner has to be the only bidder for the
   seat it just freed, so the fixture was: pause the load generator, take a QUEUED
   `BUSINESS_CLASS` entry for 2026-09-28 on `seg-01a0dde9-ccdd` whose pool was
   sold out, and release one Confirmed hold in exactly that pool
   (`POST /api/v1/capacity-holds/{id}/release`). The credit was created for the
   entry's own service, date, class and interval, and ten seconds later the entry
   was FULFILLED. What the record shows, in the same order the item asked for:

   - **the guarantee**: the entry's guarantee (`pi-e1532d23`, 9357 minor units,
     against the failed source order) had already EXPIRED with 0 captured, so it
     was never charged; the fulfilment captured exactly one payment for the
     promoted order (`pi-18684bbc`, 9357 captured, `paymentStatus=CAPTURED`);
   - **the offer window**: the order's amount equals the guarantee's amount and
     the offer's total, and the capture happened at 21:23:42, inside the
     guarantee's authorization until 21:48:36;
   - **the ticket**: `ent-01a0e4c0-a135` is ISSUED for the entry's traveler, the
     entry's segment and the promoted order;
   - **exactly one effect**: the booking `sb-383df286-cee4` has one capacity hold
     (Confirmed) with one seat allocation (CONFIRMED), and one captured intent --
     no second hold, no second capture.

   The load generator was scaled back to one afterwards, and the acceptance window
   after it is clean.

   The fixture's own by-product, recorded rather than hidden: releasing a Confirmed
   hold out of band leaves its purchase's order CONFIRMED while the seat it held
   is sold to the waitlist customer. That is what a capacity release without a
   cancellation is, and it is the state the fixture needs -- capacity cannot tell
   an operator's release from a cancellation's. The reconciliation is what would
   surface it if it mattered, the released hold's allocation is RELEASED with it,
   and the order's own post-sales paths handle a hold that is already gone (the
   release wait and its limit are T19.8's repair). The one thing the fixture did
   leave behind is a post-sales case opened by hand with an incomplete scope: it
   answers `ADJUSTMENT_QUOTE_UNAVAILABLE` and stays OPENED, inert to every journey
   the workload runs, and post-sales has no reject endpoint to terminalise it.

### T05: Prove durable event recovery

Status: **closed**, revision 500. The three items are covered by live tests run
against the deployed PostgreSQL and Redis rather than by unit doubles.

1. [x] Redis transport loss against an explicit replay source and retention
   policy. `DurableEventReplayWorker` reads a named source (endpoint plus
   producer) under `EVENT_REPLAY_RETENTION_DAYS` (1 to 90, default 7), and its
   live test deletes the stream outright and proves the worker rebuilds it from
   the durable source. Run against the deployed pair: the worker is crashed after
   the business effect but before its checkpoint, and the restarted worker
   fetches the event again and **does not repeat the effect** -- `business_effects`
   and `processed_events` both stay at 1 while `after_sequence` advances to 1.
2. [x] Notification and loyalty dead letters carry their evidence. The kit's
   `DeadLetterEvidence` records the source stream and message, the consumer group
   and name, the event id, type and producer, the **original envelope**, the
   failure kind, the reason, the attempts and both dead-letter timestamps, and
   both services wire `PostgresDeadLetterEvidenceStore`, so the evidence is
   written durably before any recovery action. `event_dead_letters` exists in
   both databases (notification on the event shard, loyalty on core) and holds 0
   rows, which matches their empty dead-letter streams; the live test
   `dead-letter evidence survives restart and preserves the original failure`
   passes against the deployed database.
3. [x] Terminal compensation mismatch checkpoints. The live test
   `CompensationMismatchCheckpointLiveTest` passes against the deployed
   database: the checkpoint survives a restart, and the restarted service
   short-circuits on it **before issuing another refund or wallet call** --
   the stub's post count does not move -- and returns the same detected-at and
   evidence. Sixteen concurrent writers to the same case all receive the
   original checkpoint rather than overwriting it. The whole post-sales suite
   with `TEST_DATABASE_URL` pointed at the deployed shard is 125 tests, 0
   failures, 0 errors.
4. [x] A pending entry that the handler completes must be acknowledged, and only
   handler failures may retire it. The Rust kit broke both halves of that: its
   pending recovery discarded the handler's answer, so a reclaimed entry was
   handled, left in the pending list and reclaimed again; and it used Redis'
   redelivery counter as the attempt count, so the five that bound handler
   failures also bound the redeliveries a rolling update causes. Measured on the
   resident release: a `BookingSagaFailed` published during the revision 480
   rollout was reclaimed five times in four minutes and dead-lettered at
   `09:36:45` although every consumer ignores that type. Both are fixed at
   revision 481 -- the recovery path acknowledges what it handled, the handler's
   own failure count decides retirement, and a separate
   `MAX_REDELIVERY_ATTEMPTS` backstop (100 by default) retires the crash-loop
   case. Verified live by scaling `entitlement-ticketing` to zero, delivering an
   entry to a consumer that no pod owns, and scaling it back: the group's pending
   list drained from 21 entries to **0** and the dead-letter population did not
   move.

### T06: Verify paid failure paths and business history

Status: **closed**, revision 496. All four legs are verified through their own
APIs, the two defects this pass found in the financial leg are fixed, and the
two reference-validation gaps it found in group booking and corporate travel
are carried as T21.

1. [x] Insurance claims and payouts. The earlier note that `PAID_OUT` and payout
   advice recorded a local decision without a disbursement call described the
   state before `16f80362`; the deployed service posts the approved amount to
   `wallet-promotion` and checks the receipt against the advice. Measured on the
   resident release (revision 478) with a delay-insurance policy issued inside
   its coverage window: `POST /api/v1/claims` with a verified delay fact and
   `settle: true` answered a claim `PAID_OUT` whose advice carries a wallet
   benefit as its `executionRef`, and `GET /api/v1/benefits/{id}` at
   wallet-promotion answers `ISSUED` stored value of exactly the approved 3000
   minor units for the policy's own account. A second approve answered the same
   completed advice and the same execution reference, and that account's benefit
   list carries **one** benefit for the claim, so the payout is neither lost nor
   repeated.
2. [x] Financial fees and reconciliations, group booking, corporate travel and
   bootstrap through the owning APIs. Measured at revision 493: fee accrual
   carries a platform fee of 15.00 with 0.90 of VAT (exactly the domain's 6%
   rate) and a changed order's revenue basis is 13682 + 4568 = 18250, equal to
   the order's own total; a group booking answers 200 with its fare reduced from
   85000 by the 5% discount basis to 80750 and its stored row matches field for
   field; a corporate agreement answers ACTIVE with a 5,000,000 CNY credit and a
   price digest that recomputes to the same sha256; and the bootstrap facts
   resolve -- 62 STATION nodes with the three interchange stations omitted,
   238,532 scheduled services with no duplicate `(origin, destination, date)`,
   and a BJN-SHQ search whose 291 minute leg is 1318 km at 300 km/h plus
   fourteen two-minute stops. Two defects surfaced here and are fixed: the
   settlement scheduler was skipped by an unreliable `@ConditionalOnBean`, and
   the operational reconciliation opened a case for two zeroes and compared a
   gross capture against net recognitions.
3. [x] Eligibility and refunds after changed travel dates and after mixed or
   repeated fare difference payments. Measured at revision 493 on a
   difference-paying change: the itinerary moved from service date 09-28 to
   09-29, the difference was assessed as 1841 plus a 1500 change fee and
   captured exactly once (one payment intent under the case, 3341), and the
   order total reconciles as 8909 + 3341 = 12250. A discounting change produced
   a 10 minor-unit difference refund that settled with refunded below captured
   and a positive refundable balance. Mixed and repeated fare-difference
   payments do not occur in this traffic: no order carries more than one APPLIED
   change, none carries both a change and a refund, and every change refund's
   funding allocation array holds exactly one allocation. That is the post-sales
   exclusion rule working rather than an unverified path; a refund raised after
   a change is covered by the Go live tests, not by the resident workload.
4. [x] Invoice amount basis and projection completion during a stable cycle.
   Measured at revision 493 on a freshly invoiced order: the basis
   (`REVENUE_RECOGNITION`, 52521) equals the order total, the invoice total and
   the captured amount, and the stored `amountBasisHash` equals a live GET's.
   The loader's own record carries 306 `invoice-amount-basis` requests with no
   404; a deliberate single-GET probe over 40 just-confirmed orders saw 39
   answers and one 404 that answered 200 two seconds later, and a second probe
   over 45 orders saw all 45 answer directly. Invoicing's outbox held no
   unpublished row of 239 and its consumer lag on `events:finance-settlement`
   and `events:journey-order` was 0. One transient gap is recorded rather than
   changed: while a refund is being applied the basis can briefly report the
   pre-refund amount, and the submit side's `amountBasisHash` check with its 412
   retry is what covers it.

### T07: Complete the reporting monetary edge cases

Status: **closed**, revision 500. Every item is implemented and was verified
against the deployed service; the one gap the pass found is carried as T23.

1. [x] `totalRevenue` is per currency, not one scalar. It answers as a list --
   measured live, `[{"amount":"116989276.70","currency":"CNY"}]` -- and the SQL
   groups by currency before it aggregates.
2. [x] `totalRevenue` is a complete total and pagination is validated against
   that answer. Measured live: `offset=0` and `offset=3` return different rows
   and the same total, with `total` reading 133423 behind it.
3. [x] Missing captured amount handling is the same on both sides. The SQL
   projection and the in-memory aggregator both require
   `PaymentCaptured AND amount IS NOT NULL` (migration 006 against
   `MetricAggregator.is_payment_captured`), and `test_gross_captured_live.py`
   runs that rule against real rows.
4. [x] The dimensions come from authoritative business references. The live view
   joins `reporting_capture_references`, `reporting_business_scopes`,
   `reporting_order_routes`, `reporting_order_travelers` and
   `reporting_seat_classes`: measured live, `seat_class` resolves to the six
   classes in use, `traveler_type` to seven, `channel` to the three channels the
   workload pays with and `route` to 133423 segments. The `unknown` bucket is the
   documented "missing is unknown, never zero" rule and held 2819 of that
   window's payments.
5. [x] The trend and operational windows reconcile. Measured live: the 13:11
   bucket's `orders_per_second` of 19.55 is exactly the 1173 orders the database
   counts for that minute, all three series carry 60 points, and the operational
   snapshot's windows are windowed SQL (60 s, 3600 s, 86400 s) rather than the
   in-memory aggregator's ten-thousand-event cap, which is reachable only for an
   unsupported `groupBy`.

### T08: Bound channel statement growth

Status: **measured**, revision 500. The lines are already stored normalised; what
the measurement separates is the generation pass from the storage shape.

1. [x] Generate, freeze and handle discrepancies, measured against a real
   channel-day. Generation built 342,648 normalised lines and took more than 43
   seconds on its first pass, while the same identity answered in 27
   milliseconds once it existed; the snapshot row is 48 kB because the lines
   live in `channel_statement_lines` (305 MB of heap and 186 MB of indexes, 933
   bytes a line); freezing answered 200 in 9.5 milliseconds; reading a statement
   took 3 milliseconds, its list 4, and its first 100 lines 0.4 seconds. The
   discrepancy lifecycle -- open, resolve, finance intake -- is covered by
   `deploy/e2e/22-paychan.sh`. A full retained day of statements is not
   reachable here: the only things that generate one are that script and the
   operations sweep, because generation is a full pass over a day of channel
   facts and no per-customer request produces a statement.
2. [x] The lines are normalised already, and the normalisation keeps identity,
   reconciliation facts and replay semantics: a NORMALIZED_V1 statement keeps its
   lines in `channel_statement_lines`, `channel_statement_identities` holds the
   identity key unique, and the statement's `statementHash` folds each line's
   evidence hash in canonical order. The read path serves both shapes, so a
   statement written before the normalisation is still readable.
   What this does NOT establish is line-level reconciliation: nothing publishes
   `ChannelStatementLineMatched`, which is T22.

### T09: Complete route feasibility and shared reference validation

Status: **closed**, revision 500. Both items are already implemented and carry a
passing test; the TODO's premise for the second one was out of date.

1. [x] Published minimum connection time rules are consumed and enforced when
   general multileg search is enabled. Multileg is opt-in per request
   (`maxConnections`), and the published SAME_STATION rule is applied to the
   connection it evaluates: `multileg_search_is_opt_in_and_enforces_the_published_mct_rule`
   seeds a 19 minute connection and a 20 minute one against a published 20
   minute rule and asserts the short one is refused while the long one comes
   back.
2. [x] The shared reference wrappers validate their boundary prefixes. The Rust
   kernel's `id_type!` macro rejects an empty value, rejects surrounding
   whitespace, and requires one of the type's own prefixes with a non-empty
   suffix -- `tvl-`, `seg-`, `plc-`, `evt-`, `corr-`, and `cmd-` or `evt-` for a
   causation id -- so a `seg-` value cannot be constructed as a `TravelerRef`.
   `cargo test` in `platform/shared-kernel-rust` is 15 tests, 0 failures.

### T10: Reconcile repository metadata and static checks

Status: **closed**, revision 500. `scripts/check-skeleton.py` passes over 39
modules, and the two suites it had been failing on were stale tests rather than
service faults.

1. [x] The catalog reconciles with the source directories, the deployment values
   and `go.work`. The check validates every entry's language against the files
   that language needs (a Go service's profile, a Java service's `pom.xml` and
   `src/main`, a Python service's `pyproject.toml`, `src` and `tests`, a Rust
   service's `Cargo.toml` and `src/lib.rs`, a TypeScript service's
   `package.json`, `tsconfig.json` and `src/index.ts`) and every path a service
   names in `docs`; the catalog's 37 services match the 37 service keys in
   `deploy/helm/train-ticket/values.yaml` exactly; and `go.work`'s `use` list
   covers every Go service the catalog names, including corporate travel's,
   group booking's and loyalty's non-Go peers by language. Two things the check
   had been failing on were fixed rather than tolerated: the TypeScript account
   service's `node_modules` held fastify 4.8.0 against its declared 5.9.0 (an
   environment repair), and its messaging test plus capacity-availability's two
   subscriber tests still asserted the dead-letter rules that T05.4 replaced.
   The check now passes; its Java leg reports `skip java checks: Java 25 is
   required, found 21` on this host, where the Java services are run under a
   JDK 25 container instead.
2. [x] The conceptual catalogs do not overclaim. `project-index.yaml` says in its
   own header that a requirement's `status: implemented` means only that the
   named code, documents and tests exist and passed their scoped checks, and that
   business scenarios, cross-service flows, deployment and load acceptance need
   their own evidence in [the TODO](TODO.md) and
   [current status](00-current-status.md).

Local Markdown links, catalog document paths and the runtime YAML values are
validated by the documentation checks. That is not a claim that every
repository check passes.

### T11: Verify wallet redemption policy enforcement

Status: **closed**, revision 481. The scope is no longer only a stored value --
`AuthoritativeBenefitScopeVerifier` resolves the referenced order, post-sales
case, offer and fare quote before a benefit is consumed, and the balances below
were measured against the resident release.

1. [x] Verify the applicable scope against the actual order. Exercised live:
   wallet-promotion read the order from journey-order, refused a request whose
   business reason did not name that order (`PRECONDITION_FAILED: businessReason
   does not match the verified useReference`), and refused a cancellation or a
   segment the order does not carry. The `ANY_TRIP` benefit used for the run is
   the loosest scope; `ORDER`, `POST_SALES_CASE`, `SERVICE_SEGMENT` and
   `PRODUCT_CATEGORY` each resolve their own reference and compare it to the
   benefit's `referenceIds`.
2. [x] Verify partial and reserved redemption, expiry and reversal. Measured on
   a 3000 minor unit benefit, with every number read back from the benefit and
   the wallet account:

   | Step | Status | Available | Reserved | Redeemed |
   |---|---|---|---|---|
   | issue | ISSUED | 3000 | 0 | 0 |
   | reserve 1000 | RESERVED | 2000 | 1000 | 0 |
   | reserve 1000 again, same ref | RESERVED | 2000 | 1000 | 0 |
   | redeem 1000 from the reservation | ISSUED | 2000 | 0 | 1000 |
   | redeem again, same ref | ISSUED | 2000 | 0 | 1000 |
   | reverse the redemption | REVERSED | 3000 | 0 | 0 |
   | reverse again, same ref | REVERSED | 3000 | 0 | 0 |
   | reserve 500, then release it | RELEASED | 3000 | 0 | 0 |
   | reserve 400 expiring in 6 seconds | RESERVED | 2600 | 400 | 0 |
   | 7 seconds later, sweep | RELEASED | 3000 | 0 | 0 |

   Every replay answered the same numbers, so a retry neither duplicates a
   redemption nor an issuance; the wallet account's `STORED_VALUE` balance
   agrees with the benefit at 3000 available, 0 reserved, 0 redeemed; and
   `issued = available + reserved + redeemed` holds at every step, which is the
   invariant the domain asserts on each transition.

### T14: Verify change sagas finish inside the client polling window

Status: **closed**, revision 479. The tail was the reservation hop, and the
hop-by-hop measurements that attributed and removed it are recorded in
[current status](00-current-status.md#the-reservation-poll-stopped-failing-and-what-was-left-behind-it).

1. [x] Attribute the gap between the capacity hold request and the difference
   payment creation. Per hop, over 2026-09-27: the hold request reached capacity
   in 0.2 to 0.3 seconds, the hold's *confirmation* was the late one at 12.0
   seconds median and 16.9 at the ninety-fifth percentile, and
   `booking-orchestration`'s oldest undelivered entry on
   `events:capacity-availability` was 10.8 seconds old at the same moment. That
   is the hop the change case waits on, and it is the same hop the purchase
   journey's reservation poll waits on.
2. [x] Reduce that gap below the polling budget without lengthening the client
   polling. The reader loops, the shared handler pools and the replica counts
   that took the reservation tail to zero are the same ones this hop needed; no
   load generator poll interval or budget changed.
3. [x] Re-measure the change journey duration distribution afterwards. At
   revision 479, over one whole diurnal cycle: **924 change journeys, 0 failed**,
   duration 3.3 seconds at the median, 4.8 at the ninety-fifth percentile and
   8.0 at the maximum, against the client's ten polls of one second. The
   ninety-fifth percentile sits at half the budget and the slowest case of the
   whole window finishes with two seconds to spare.

## Telemetry follow-ups

### T18: Finish low-cardinality business telemetry

Status: **closed**, revision 515. Every source metric in the contract is
implemented and deployed, every source and gauge carries the cardinality and
restart tests the contract promises, and the scheduled-job collector's two
halves are published: the age of a job's last success, and the age of the oldest
due task.

1. [x] Publish the `search` and `quote` stages of the order funnel, which no
   service emitted. The contract had named offer management as the owner, but
   offer management serves neither: it has one create endpoint that builds an
   offer from an itinerary and a quote another service already consumed, so
   counting its offers under either stage would have made every offer attempt
   read as a search. Each stage is emitted by the service that serves it --
   `search` by trip-planning, `quote` by fare-pricing -- and
   [the contract](07-observability/business-metrics.md#implementation-ownership)
   is corrected to say so. Every exit from either handler records one
   `attempted` and exactly one outcome: trip-planning's is a guard that records
   the rejection when it is dropped, so the early validation returns and a
   storage failure propagated with `?` all count, and fare-pricing records the
   rejection on each of its error exits. Measured at revision 480, the counter
   carries all six stages -- `search`, `quote`, `order`, `reservation`,
   `payment`, `ticket` -- in 24 series, every one of them under the contract's
   three labels with no value outside its vocabulary.
2. [x] Port the Java event-consumer and outbox metric contract to the Go,
   Python, TypeScript and Rust platform kits. All seven instruments now exist in
   every kit under the same names, units and closed vocabularies -- messages by
   `outcome`, failures and dead letters by `reason_code`, and the outbox's
   bounded unpublished head sample, oldest unpublished age, published rows and
   per-stage failures. Measured at revision 483: `messages` is published by 27
   services and `outbox.published` by 35, one per service that runs a consumer
   or a relay, and a ten minute increase reads 898980 `success`, 6 `duplicate`
   and 201 `retry`, with `failures{reason_code="handler_exception"}` naming
   travel-insurance's deliberate wait for a post-sales case. Three services have
   no consumer at all by design (place-network is a producer only,
   supplier-catalog's subscription list is empty, and dispatch, payment-channel
   and service-plan never subscribe), so the absence of a `messages` series is
   the correct reading for them.
3. [x] A cardinality test for every source, and a restart test for every gauge
   derived from durable state. Every source now has one that records two
   different business facts and proves the label set does not grow:

   | Source | Test |
   | --- | --- |
   | `order.transitions` search / quote | `two_different_searches_record_the_same_label_set` (trip-planning), `test_two_different_quotes_record_the_same_label_set` (fare-pricing) |
   | `order.transitions` order | `OrderFunnelCardinalityTest` (two orders, two accounts, two offers) |
   | `order.transitions` reservation / payment / ticket | `BookingOrderFunnelCardinalityTest` (two sagas reserved) |
   | `booking.saga.active`, `.oldest_age` | `BookingSagaStageReporterTest` (three saga ids through the reporter) plus its restart case |
   | `booking.saga.transitions` | `twoDifferentSagasCollapseOntoOneSeries` |
   | `payment.operations`, `.amount` | `twoIntentsCaptureUnderTheSameLabelSetAndNeverLeakTheirIds` (two intents through the service) |
   | `payment.conservation` | `PaymentConservationTest` (two currencies, and a restart) |
   | `payment.operations` on the channel edge | `service_metrics_test.go` (two orders) |
   | `payment.operations` compensation | `twoDifferentCompensationCasesProduceTheSameLabelSet` (two cases through the service) |
   | `capacity.units`, `.hold.oldest_age` | `different_identifiers_produce_an_identical_label_set` (two pools, holds and segments), plus its restart case |
   | `seat.allocations` | `seat_allocations_test.go` (two allocations) plus its restart case |
   | `ticketing.transitions`, `ticketing.active` | `different_identifiers_produce_an_identical_label_set` (an issuance and a void) and `a_restart_reconstructs_the_same_active_level_instead_of_a_reset` |
   | `ticketing.transitions` validation | `TestDifferentIdentifiersProduceAnIdenticalLabelSet` and `TestTwoDifferentRecordsPublishTheSameLabelSet` (two records through the service) |
   | `waitlist.intents`, `.oldest_age`, `.promotions` | `metrics.test.ts` (two intents, two entries, two promotions) |
   | `wallet.balance` | `WalletBusinessMetricsTest` (two accounts, and a restart) |
   | `wallet.operations` | `operationsAreRecordedWithBoundedOutcomesAndNoIdentifiers` (a benefit id through the service) |
   | `notification.queue.tasks`, `.oldest_age` | the queue-depth tests, including a restart that rebuilds the same gauges |
   | `notification.retries` | `metrics.test.ts` |
   | `event.consumer.*`, `event.dlq.entries`, `event.transport.series` | the collector's tests, including its own restart case |
4. [x] Give the transport collector a guard on its own series count, so a new
   stream or group cannot silently multiply the label set. The collector's three
   transport gauges are labelled by stream and consumer group, so their width is
   the transport's width rather than the services': it now publishes the sorted
   head of its own output past `TRANSPORT_MAX_GROUP_SERIES` (400) or
   `TRANSPORT_MAX_DEAD_LETTER_SERIES` (400) and reports the remainder as
   `train_ticket.event.transport.series{kind="group_dropped"}`, which is a series
   an alert reads rather than a silence. Measured at revision 485:
   `series{kind="group"}` is 123 against 123 `consumer.lag` series and
   `group_dropped` is 0, `dead_letter` is 6, `dead_letter_dropped` is 0.

   The guard also found the reason a new label could go missing: Prometheus'
   Deployment hashed only its values, so the scrape configuration -- the sample
   limits and the label allowlist -- never rolled the pod. Measured: the running
   config was 22 hours old while the ConfigMap beside it held the new one, and
   the collector's new `kind` label arrived stripped. The rendered config is
   hashed now, so changing it rolls Prometheus.
5. [x] Add a collector that reports scheduled job success and the age of the
   oldest due task; several sweeps are only observable through their effects.

   First slice delivered at revision 509, verified at 511: `finance-settlement`
   publishes `train_ticket.scheduled_job.success_age` in seconds for
   `DAILY_RECONCILIATION` and `SUPPLIER_SETTLEMENT`, rebuilt from
   `settlement_schedule_runs` on its own daemon thread so a restart reads the
   durable last success instead of resetting it, and with **no series at all**
   for a job that has never succeeded rather than a zero that reads as success.
   Measured through Prometheus at revision 511: both labels present on every
   replica. Two things had to be right for that to be readable. The label is
   `scheduled_job` and not `job`, because the OTel Prometheus exporter drops a
   datapoint attribute that collides with the constant target label `job`, and
   it is named in the scrape's `labelkeep` regex as well as in the collector's
   datapoint allowlist -- the first published series reached Prometheus with no
   label at all, because the two allowlists are separate copies of the same
   contract and only the collector's had been updated. A chart test now asserts
   that every label the collector keeps survives the scrape.

   What remains: the oldest-due-task half, and the same family for
   booking-orchestration's saga step deadlines and payment's intent expiry.

   Second slice delivered at revision 515: both are published.
   `train_ticket.payment.intent_expiry_age` is the age of the oldest intent past
   its expiry that the one-second sweep has not expired, read before each sweep
   (12 milliseconds against 1.17 million intents) and rebuilt from the intent
   rows so a restart republishes it; measured through Prometheus at revision 515
   it is present. `train_ticket.booking.saga.step_due_age` is the same reading
   for the saga step-timeout queue, rebuilt by the leader-elected stage reporter
   from the indexed deadline table; measured live it publishes nothing, which is
   the healthy reading -- of the 80 pending deadlines, **0** were due, because
   the one-second sweep retires them faster than the reporter looks. Both clear
   their reading when the queue empties rather than holding a stale age.

### T23: Make `yieldPerKm` a number rather than a constant zero

Status: **closed**, revision 504. The field is a number and it describes one
population.

It was `0.00` on every row because the view read the distance from the capture's
own flags and no capture carries one: fare-pricing is where a distance exists,
and reporting does not consume that stream. The distance now travels the chain
that reporting already reads -- fare-pricing's quote, kept by offer-management
and published on the offer, read by journey-order's offer adapter and carried on
`JourneyOrderCreated` -- and migration 011 rebuilds the view to fall back to the
order's distance.

1. [x] Give the view a distance. Measured at revision 504: new order rows carry
   one (1516 of 3030 in a three minute window; the rest are orders whose quote
   was priced from the flat base fare, which have none to carry), and the
   report reads SECOND_CLASS 0.13, FIRST_CLASS 0.23, BUSINESS_CLASS 0.44,
   SLEEPER_HARD 0.23, SLEEPER_SOFT 0.29 and STANDING 0.09 CNY per km -- against
   the platform's own 0.15/km base rate with the 2.8 class multiplier visible in
   the business-class figure.
2. [x] Or remove the field. Not needed: it is a measurement now. One caveat is
   recorded rather than hidden, and migration 012 handles it: a row can mix
   orders whose quote carried a distance with orders whose quote did not, so the
   numerator is the amount of the same captures that contributed distance.
   Without that pairing the mixed population read 349.85 CNY/km, which describes
   neither group.

### T22: Match a channel statement line to the order it settles

Status: **closed**, revision 515. The channel side is read rather than published,
the day's reconciliation is complete against a real statement, and the two
defects the first real run exposed are fixed.

Everything below was written while the item was open and is kept because each
line is still the reason the area behaves as it does.

finance-settlement records that event (`recordChannelStatementLine`) and nothing
publishes it, so finance's `channel_statement_lines` is empty and
`runDailyReconciliation` falls back to one statement-level fact per channel. The
platform's captures for the day then have nothing to match against, and every
one of them is entered as missing in channel -- a report that says the whole day
is a discrepancy. The statement is the only place the channel and the platform
agree on the same money, so a statement that cannot be matched line by line
cannot reconcile.

Measured at revision 500, after the operations sweep began generating the day's
statement: one ALIPAY_SIM statement with 342,648 lines sits in payment-channel,
finance holds the statement row and 0 lines, and the daily batch's first run
predates both.

Updated at revision 511: the statement itself now reaches finance, and what is
left is exactly the line level. Until T24 the freeze announcement dead-lettered,
so finance held no statement at all; measured at revision 511, three statements
generated and frozen through the API are stored `FROZEN` with their amounts and
generation instants, which is what makes the fallback below real rather than
empty. The fallback is still one statement-level fact per channel, so the
platform's captures for the day are still entered as missing in channel one by
one -- one row per capture rather than one row per line -- and that is the item
this section is about.

1. [x] Publish the fact, or replace it with something that scales. The shape is
   the second one the item named: finance reads the statement payment-channel
   already stores, 500 lines a page by ordinal cursor, once per statement (a
   statement whose lines are recorded is skipped) and idempotently by statement
   line id. The first read of a statement resolves orders one query per page
   rather than one per line, which took a real 165,724-line statement from 575
   to 95 seconds.
2. [x] Then verify the daily batch against a real statement. Verified at
   revision 515 on the whole closed day 2026-09-26: 618,650 channel lines read
   from the three frozen statements (322,796 + 143,955 + 151,899), every
   statement's payment lines folding back to its own published gross payment and
   its refund lines to its gross refund, and the reconciliation itself reading
   **488,414 entries, 488,414 matched, variance 0.00, 0 exceptions**
   (`ChannelStatementIngestLiveTest` reproduces the read against the deployed
   release).

   That run is also what found the two defects the first attempt hid. The first
   batch died on its own size -- 431,530 entries is more than one PostgreSQL
   jsonb array will hold, so the snapshot could not be written at all -- and the
   batch now keeps its report and the entries that need attention rather than
   every entry. The second was the key: keyed on the order, 56,884 of 431,530
   entries were amount mismatches, and every one was an order that had paid
   twice, because the platform attributes a fare to the order and a change
   difference to the post-sales case while the channel knows only the payment
   intent. The key is the payment intent, and a change difference payment is now
   recorded as the receipt it is (migration 011 repairs the historical days from
   the platform's own recognitions).

   What is deliberately not compared: the statement's `REFUND` lines. They are
   recorded with their type, and the capture comparison reads payment lines only
   -- a refund line is the channel's answer to a platform refund, and comparing
   it with a capture would report every refund as unexplainable. Matching the
   refund side of a statement is its own item, in
   [T25](#t25-match-the-refund-side-of-a-channel-statement).

### T24: The frozen channel statement reaches finance

Status: **closed**, revision 511. Three defects, each measured live before and
after; the narrative and the raw numbers are in
[current status](00-current-status.md#the-channel-statement-reaches-finance-and-a-read-that-trails-its-announcement).

1. [x] The freeze no longer dead-letters. A statement is announced twice --
   generated, then frozen -- and the freeze carried only the transition while
   finance's projection read it as if it restated the statement, so `money()`
   threw `grossPaymentAmount is required`, the handler classified it fatal and
   every freeze died on its first delivery: measured, one
   `events:payment-channel:dlq` entry per channel (16:25 `UNIONPAY_SIM`, 16:51
   `WECHAT_SIM`) while the generated rows sat correct in `channel_statements`.
   payment-channel's freeze now carries the statement's own facts and finance
   merges the payload with the stored row.
2. [x] The projection is monotonic. Both announcements go to the outbox in one
   relay pass and the group runs one consumer per replica, so they are applied
   in either order; reproduced live, a statement frozen through the API was
   stored `GENERATED` with a null `frozen_at` while its sibling landed
   correctly. The upsert now decides it atomically -- a row that is already
   frozen stays frozen and a null `frozen_at` never overwrites one -- and the
   handler keeps the sequential guard. `ChannelStatementProjectionLiveTest` runs
   that interleaving against the deployed PostgreSQL.
3. [x] Verified through the API, not only in tests. Measured at revision 511:
   three statements generated and frozen through `payment-channel` are stored
   `FROZEN` with their amounts and generation instants, the dead letter did not
   grow, and the day's statement is now what the daily reconciliation's
   statement-level fallback compares against.

The line level is deliberately not part of this: see
[T22](#t22-match-a-channel-statement-line-to-the-order-it-settles).

### T25: Match the refund side of a channel statement

Status: **closed**, revision 519. The two sides are compared per refund id, and
the whole day reconciles.

What it needed, and what it got. A statement line of type `REFUND` carries the
platform refund it settles, so the comparison key exists on the channel side;
what did not exist was the platform's own answer for a settlement date. Payment
now keeps `refund_settlements` -- written in the same transaction as the
settlement, backfilled by its migration 013 from the `RefundSettled` events the
refund snapshots already carry, and read a page at a time by
`GET /api/v1/internal/refund-settlements` in the index's own order. Finance
stores the refund id on the channel line (migration 012) and compares both sides
per refund: matched, amount mismatch, or present on one side only.

Measured at revision 519 on the whole of 2026-09-26, with the three channel
statements read again from payment-channel: **618650 entries** (488414 payments
and 130236 refunds), **618479 matched**, **0 unexplained on either side**, and
**171 amount mismatches**. Those 171 are the workload's own injected channel
faults -- a refund the channel paid back at exactly half the platform's amount --
which is the break the reconciliation exists to find, and the reason the day is
the strongest evidence this item could produce.

One defect the first cut of it hid, worth keeping: the platform side is paged,
and the page bound was 100 pages of 1000. The measured day has 130236 refunds,
so the last 30236 were never read and the run reported them as refunds the
platform had never settled -- an unread page is indistinguishable from a missing
refund. The bound is now a million with a log line if it is ever reached.

### T21: Resolve the references a booking and an agreement are created with

Status: **closed**, revision 497. Both creation paths resolved their references
and both journeys now supply real ones.

1. [x] Group Booking resolves its `segmentRefs`. It stored and priced a booking
   whatever the segments were: measured at revision 493, `seg-group-<uuid8>` --
   a reference service-plan answers 404 for -- was quoted (85000 reduced by its
   500 basis points to 80750) across 84,720 bookings. The service now asks
   service-plan for every segment and refuses the booking when one is unknown,
   and the load generator's journey searches for a real trip first. Measured at
   revision 497: the newest bookings carry segment refs that answer 200 from
   service-plan, and the journey still completes (`group_booking` 99 of 99 in a
   seven minute window).
2. [x] Corporate Travel resolves its `priceRef.ruleSetId`. It folded the id into
   the agreement's price digest without asking: measured at revision 493, all
   127,064 ACTIVE agreements carried `rs-<uuid8>` values no published rule set
   matched. fare-pricing now answers `GET /fare-rule-sets/{id}` in any status,
   the service refuses a reference it cannot resolve, and the journey takes the
   rule set its own quote applied. Measured at revision 497: the newest
   agreements carry `ruleset-default-0-WEB`, `-2-MOBILE` and `-3-COUNTER`, each
   answering 200, and the journey still completes (165 of 166 in the window that
   spans one pod rollover, the one failure a refused connection during it).

## Operational follow-ups

These are not defects in the code as shipped; they are the next step for a read
that was made safe rather than fast.

Items 1 and 2 are **deferred by the operator's instruction**, not forgotten: the
traffic level is fixed and further throughput work is out of scope for this pass.
Both reads are already bounded by their cadence rather than by their cost -- the
conservation refresh runs every ten minutes on its own daemon thread, and the
capacity fold runs every five minutes and streams its rows so it holds constant
memory -- so what is left in each is an optimization with no user-visible effect
at those cadences. The same instruction defers T01's four bus items and T19's
four remaining items (Trip Planning's peak search, the shard cutovers, the write
amplification and the pool-lock admission), and it is what closes the two
burst-level sections further down: the acceptance no longer runs at the envelope
those measured.

1. [ ] Payment conservation: the aggregate over `payment_intent_snapshots` is
   still planned as a parallel sequential scan even with the covering index
   present, because the snapshot writes keep the visibility map sparse.
   Measured: 33 seconds with the index, 59 without. Vacuuming the table, or
   maintaining the totals incrementally, would let the refresh return to a
   useful cadence.
   Re-measured at revision 505 after `VACUUM (ANALYZE)`: the visibility map is
   now 100 percent of the table's pages (`relallvisible` 260567 of 260654) and
   the plan is an `Index Scan using payment_intent_captured_amount_idx`, not a
   sequential scan, at **16 seconds** for the captured aggregate against the
   covering index. The remaining cost is the per-row expression evaluation over
   1.17 million rows, not the heap access.
2. [ ] Capacity levels: the fold streams rather than materialises, so it is
   constant memory, but it still reads seven hundred thousand pool documents
   (about five seconds). Pushing the fold into SQL would cut the transfer to the
   label combinations alone.
3. [x] Notification backlog is bounded and the quota is enforced and visible.
   The delivery path checks a per-recipient limit before it sends -- `SMS` 5 a
   day, `EMAIL` 20 a day, `PUSH` 10 an hour, `IN_APP` 50 an hour, plus a global
   `SMS` 1000 a minute -- through a durable store in production
   (`PostgresRateLimitRepository`), so the limit is shared by both replicas and
   survives a restart rather than being per-process. A refusal does not drop the
   message: the delivery queue re-arms the row at `retryAfter`, records a
   `QuotaDeferred` evidence entry and counts
   `train_ticket.notification.retries{outcome="deferred",reason_code="recipient_quota"}`,
   which is exactly what the reserved reason code exists for. What is left is a
   throughput question rather than a visibility one, and the queue depth and
   oldest age gauges answer it.
4. [x] Historical `processed_events.stream` rows are gone rather than rewritten.
   The kit's retention sweep deletes them in batches by `processed_at`, and
   measured live: `waitlist`'s table holds **0 rows with a blank stream** out of
   7788. New rows carry the stream, and the old ones are not worth rewriting.
5. [x] Historical hold unit binding does not occur in this environment.
   Measured live: of 1344258 holds, **0 lack `supplyEvidence`**, and the 8110
   that carry none are `Failed` purchase holds -- a refused admission never
   bound a unit, so it has no composition to name. Every live hold carries
   `compositionVersion`, `seatMapId` and the stop-sequence version the item's
   backfill would have reconstructed. A unit that predates `supplyEvidence` is
   still handled the way the code says: admission leaves it out of the occupancy
   fold on both sides of the comparison rather than failing or inventing a unit.
6. [x] Post Sales: `FarePricingAdjustmentQuoteClient.compute` answered 500
   `IllegalStateException: target offer travelers differ from the original
   order` when a change case's target offer did not carry exactly the order's
   traveler refs. Measured at 56 occurrences in three minutes while reservation
   failures were also happening, which was the candidate cause: a purchase whose
   reservation never confirmed has fewer tickets than the order has travelers.
   **Re-measured at revision 479 with the reservation path clean: zero
   occurrences in the whole resident cycle.** The check stays as a guard against
   a genuinely mismatched offer rather than a symptom of the reservation tail.
7. [x] `events:booking-orchestration:dlq` held about eight thousand entries that
   exhausted their delivery attempts while capacity rejected reservation
   sources, which is a defect that is now fixed. Measured at revision 478 the
   stream holds no entry at all, so the dead-letter population is once again an
   alarm rather than an archive.
8. [x] `events:booking-orchestration` carried a `notification` consumer group
   with a lag of 30003 entries that no service could ever clear: notification's
   subscription to that stream was dropped deliberately (no event type it
   renders is published there) but the Redis group outlives the configuration
   that created it. Dropping a subscription in a service's stream list is
   therefore not a complete change -- the group has to be destroyed on the bus
   as well. Measured at revision 478 the group is gone and the lag table reports
   three live groups at zero or one entry.
9. [x] Travel insurance retries two valid events as transient failures rather
   than dead-lettering them, and both eventually succeed: a change that
   `journey-order` has announced before post-sales reached EXECUTING answers
   "applied change scope is required" (six in fifteen minutes at the fixed
   level), and a purchase reference payment has not written yet answers 404
   (four). Each retry holds the entry pending for the reclaim window and costs a
   read of the owning service, and the same path is bounded by
   `MAX_DELIVERY_ATTEMPTS`, so a case that never executes still ends in the dead
   letter -- the three entries in `events:journey-order:dlq` are exactly that.
   Measured at revision 478 no new entry reached either dead letter across a
   whole cycle, so this is a wait that works rather than a defect the acceptance
   level reaches. The next step, if it is worth taking, is to stop reading a
   case that is only QUOTED or APPROVED and to let its own execution announcement
   drive the adjustment.

   Re-measured at revision 504, and the shape is unchanged: the two waits are
   still there at **0.5 first-delivery retries a second**, every one of them
   `deliveries=1` -- a new announcement whose owning service has not caught up,
   not a redelivery of a bad message -- and neither dead letter has grown
   (`events:journey-order:dlq` holds its 3 from the pre-fix rule,
   `events:post-sales:dlq` holds 0). The refinement named above stays a design
   choice rather than a defect: driving the adjustment from the applied
   announcement instead of from the order's would remove the retry, and it is
   not required for the wait to be correct.

   **Taken at revision 511, and it was a defect rather than a design choice.**
   Classifying the wait as an *unclassified* error meant 55 of 66 failures in
   ninety seconds were recorded as handler faults, and the race is structural:
   measured, post-sales commits a change case's applied snapshot 5 milliseconds
   *after* journey-order appends the announcement that wakes the handler, so the
   first read always trails it. The wait is now explicit -- `ErrNotReady` for a
   case that is only quoted or approved and for a 404 on a reference the
   announcement named, retried five times 400 milliseconds apart inside the
   delivery, then handed back as a declared transient failure -- so the metric
   and the log say what happened. Measured after: **3 failures in ten minutes**
   against about 40 a minute, and the three are one message whose case took
   minutes to reach APPLIED and which then succeeded.

## Handoff rules

No unchecked item above is implicitly authorised: completing one means
implementing it, deploying it, and attaching the code, deployment and business
verification evidence to the item before it is marked done.

Runtime configuration is maintained in exactly one file,
`deploy/helm/train-ticket/values.yaml`. The former cluster, case, resident,
production and ACR overlays are deleted.

## Invoicing amount basis, measured 2026-09-26

The window at revision 421 carries 42 `invoice-amount-basis` failures: a GET of
`/api/v1/invoice-amount-bases/{orderId}` answered 404 "amount basis not yet
available" on the last of the client's ten one-second polls. The client is not the
problem -- `journey_invoice.go` expects 404 on the first nine attempts and only
the tenth counts -- and neither is a backlog: invoicing is at lag 0 on
`events:journey-order`, `events:payment`, `events:booking-orchestration` and
`events:post-sales`, and finance-settlement is at lag 0 on payment,
booking-orchestration, post-sales and provider-integration. That is a different
shape from the offer failures, which were a group reading slower than its stream.

The 404 comes from a projection lookup with nothing in it
(`services/invoicing/src/application.rs:630`, and its in-memory twin at :627), and
the entries are written by event handlers: a revenue recognition around line 396
and a reversal around line 343 both `insert` into the amount map and record the
event id. So the next question is the one that decides the fix, and it is one
read: which event creates the *first* basis for an order, and whether every
purchase emits it. If some orders legitimately have none -- one whose payment
never completed, say -- then the 404 is correct and the client's unconditional
expectation of a basis is the defect. If every purchase should have one, this is
the same race as the refund: a reader asking before the writer's event arrived.

Answered: the event is `RevenueRecognized`, emitted by finance-settlement, and
invoicing's handler for it (application.rs:348) is what creates the first basis.
finance-settlement is at lag 0 on every stream it reads, so the basis is absent
because revenue was never recognized for that order -- which is the correct state
for one whose payment did not capture, and a 404 is then the honest answer rather
than a latency. The load generator's branch is the thing to read against that:
`MaybeRequestInvoice` is called with a purchase and only skips on
`TotalMinor <= 0`, so if it runs for purchases that never captured, the client is
asking for a document the order cannot have and its expectation is the defect.

Read, and it is not the client's expectation either. `MaybeRequestInvoice` is
called at `journey_purchase.go:293`, and the only path to it is past
`awaitPaymentCaptured`: when that returns not-paid the journey returns
"payment_rejected" at line 216, before the invoice branch. So the 42 orders had a
captured payment and no recognized revenue, which is a gap rather than a
misplaced question. The thing to check next is the fault the generator injects on
that path -- `faultSeed = "MISSED_ORDER:loadgen"` at line 204 -- because a capture
that succeeds while the order signal is deliberately missed is exactly an order
whose revenue finance-settlement may never recognise.

Answered: `MISSED_ORDER` is a deliberate fault. `services/payment-channel/internal/domain/order.go:78`
puts the channel order in `StatusMissed` and emits `ChannelOrderMissed` instead of
accepting it, so the capture the generator asked for is one the channel
deliberately did not place. The invoice branch then asks for a document that this
order cannot have.

What that leaves is not a service defect but a question the acceptance has to
answer, and it should be answered deliberately rather than by editing the client
to stop asking: either a missed channel order should not report the payment as
captured at all -- in which case `awaitPaymentCaptured` answering paid is the real
inconsistency, and the invoice branch is its first visible symptom -- or a missed
order is a captured-but-unreconciled state by design, in which case a fault-seeded
purchase should not be asked for a basis and the generator's branch is what is
wrong. The 42 failures are the seeded fault's outcome either way, so they should
not be counted as an unexplained user-visible failure while that question is open.

Answered by the code, and the service is right: payment treats a missed channel
order exactly like a failed one -- `PaymentInboundEventHandler.java:121` routes
`ChannelOrderMissed` to `handleChannelOrderFailed`, which calls
`failIntentFromChannel`. An order the channel did not place does **not** leave the
intent captured.

So the client's earlier reading is the race: `awaitPaymentCaptured` saw the intent
as captured and the journey went on to the invoice branch, while the
`ChannelOrderMissed` that fails the intent arrived afterwards. That also says what
the fix is, and it is the client's: when the amount basis is still absent on the
last poll, re-read the payment intent, and if the capture that this journey
observed has since failed, report the purchase as an unfulfilled payment rather
than asking for a document it cannot have. Teaching the client to skip a basis
whose order was fault-seeded would hide the race instead of handling it.

### The saturated journey-order dead letter is travel-insurance's (2026-09-26)

`events:journey-order:dlq` holds 30001 entries -- its cap, so the oldest have been
trimmed and the count is a floor -- and the newest are `travel-insurance` failing
`JourneyOrderPostSalesAdjusted` with `domain rule violation: applied change scope
is required`, five attempts each (`consumerGroup=travel-insurance`,
`consumerName=travel-insurance-1`). Every post-sales adjustment on an insured
order therefore dead letters, and the insurance projection is missing exactly the
orders that were changed.

Two things follow. It is a defect of its own, and it is not the invoicing gap: the
group that dead letters is travel-insurance, while invoicing reads finance
settlement's recognition, so the captured-payment-without-recognition question
above is still open. It is also the largest single population of lost events in
the release, and the reason to fix it is the same as for the capacity releases:
an event that dead letters is an effect that silently never happens.

Fixed and verified at revision 423. The case type is a named outcome now
(`ErrNotChangeAdjustment`) that the handler acknowledges, and the check is
measured by the dead letter's own clock: its newest entry is `11:11:43Z`, the
deployment completed at `11:07Z`, and at `11:19:30Z` -- eight and a half minutes
later, with the load generator running -- nothing newer had arrived. The 11:11
entry is the tail of a retry chain that began before the rollout: five attempts
with backoff outlive a deploy, and the call site is the one that was edited (the
message appears twice, both inside `ResolveJourneyCoverage`), so nothing else
produces it.

Read, and it is deterministic rather than load-related. `JourneyOrderPostSalesAdjusted`
carries a `postSalesCaseId`, and travel-insurance handles it by reading that case
and requiring `caseType == "CHANGE"` with a non-empty scope
(`application/purchase_verification.go:166`). A **refund or cancellation** is
neither, so every adjustment of those kinds fails with "applied change scope is
required" by construction -- and the payloads in the dead letter agree, their
`monetarySummary.cancelledTotal` equalling the whole subtotal. This is the
insurance domain accepting only one of the three case types journey-order
announces.

What it needs is a decision before a fix, and it should be taken the way the
standing-capacity policy was: a refund or cancellation ends the coverage the order
carried, so the handler should void it for the case's scope and record why, rather
than treating "not a change" as a rule violation. Until that decision is made the
insurance projection keeps missing exactly the orders that were refunded or
cancelled, and the dead letter stays at its cap with the oldest entries already
trimmed.

## Hardcoded time and location values, audited 2026-09-26

Three services carried a value where a fact belonged. Each is fixed in its own
commit, deployed at revision 425, and measured (see
[current status](00-current-status.md#hardcoded-values-audit)): the load
generator's traveler creations carry `dateOfBirth` and the read returns it, a
create without one returns `null`, a document without an issuing country is a
400, and the revision 425 window is 0.19 percent failed journeys against 0.20
percent at revision 421. Two items from the same audit are still open.

1. [x] `deploy/stress` pinned `departure_date: "2026-09-01"` in every scenario
   and as the driver's default, which was already a departed service day. The
   scenarios no longer set it and the driver defaults to a week past the run
   clock.
2. [x] The load generator's invoice branch sends `titleName: "个人"` as a fixed
   personal-invoice title. It is test tooling, and the value is the business's
   own label rather than a location or a date, so it stays; this is recorded so a
   later reader does not re-open it as a finding.

## The burst is the acceptance's remaining shape, measured 2026-09-26

**Closed by the level, not by the items below.** This section measured the
envelope the release ran at *then*: a diurnal peak of 4.0 and a burst of up to
8.0 on top of `target_rps`, whose minutes carried 15000 to 26326 journeys. The
operator fixed the traffic level at the measured knee -- peak 2.0, burst 1.5 to
2.0, product 4.0, in `values.yaml` -- so the acceptance no longer reaches those
minutes, and the items below are the record of what that shape cost. Every
acceptance window since revision 511 carries 0 failed journeys and 0 unexpected
HTTP errors at the peak and the trough of the fixed level, so what remains here
is a description of a level the release no longer runs at rather than work in
the backlog.

The split removed the instance-wide half of this: risk assessment went from a
15 second ninety-fifth percentile at every burst to 0.27 seconds, no consumer
group stays above 50 entries of lag, and the first window after the reset is
clean (0 failed journeys of 47671). What the burst still does is exceed the
request-serving replicas: the next window carried 18828 journeys in one minute
and failed 4194, dominated by fare-pricing quotes at the client's 10 second
timeout (1458), journey-order 503 "Unable to resolve authoritative order
snapshot" (606) and post-sales timeouts (523).

1. [ ] Size the request-serving replicas for the burst. No service in the chart
   has an HPA and the values file carries fixed replicas; fare-pricing runs one,
   post-sales two, journey-order two. Revision 430 raised the three pools the
   burst exhausted, and the burst window now carries no timeout and no 503 (see
   [current status](00-current-status.md#every-service-is-on-its-shard)), so what
   is left here is deciding whether the fixed replica counts also need to move:
   fare-pricing held six of its eight cores during the burst and post-sales
   holds two pods, so an HPA on the request-serving pools is the candidate, or a
   fixed count the burst cannot exhaust. Measure it against a window chosen for
   the burst minute -- the cycle's peak is not in every ten minute window.
2. [ ] The refund tail is separate from the burst and is already tracked above:
   363 `refund-status` and 197 `case-status` failures in the revision 426 window
   are spread across it, with the same shape in the revision 425 window (53 and
   17). Measuring the refund path's latency before attributing them is the item
   at the top of this file's post-sales section.
3. [ ] The refund tail is measured and it is late, not stuck. Every case the
   revision 430 window reported as "did not reach SETTLED" or "did not reach
   APPLIED" (189 of 189) is APPLIED now, and every one has a SETTLED refund in
   payment. The margin is about a second and it is inside payment, not the
   channel:

   - the case reaches APPLIED 5.2 seconds after it is created (inside the
     client's ten second case poll);
   - payment records `RefundRequested` 3.2 seconds later, and `RefundSettled`
     7.9 seconds after that -- 11.1 seconds after APPLIED, against the client's
     ten second refund poll, so the request lands a fraction of a second late;
   - payment-channel is not the slow part: its refund snapshot shows create and
     `SUBMIT` ten milliseconds apart, and the typical refund settles 0.25
     seconds after `RefundRequested` (403 to 517 refunds per 0.05 second bucket),
     so the 7.9 seconds is a tail inside payment's settle path;
   - the tail is a population, not one bad case: of 17371 settled refunds,
     14795 settle under a second, 1048 take one to three, 653 take three to
     eight, 311 take eight to twelve and 564 take more than twelve (mean 20.5
     seconds). The slow ones are dominated by `post-sales-approved` (721) and
     `CHANGE_FARE_DIFFERENCE` (404), which is the path this file's refund items
     measure;
   - the same apply path also completes a case whose own execution plan still
     lists `REQUEST_REFUND` as `PLANNED`
     (`PostSalesApplicationService.applyForSegmentBooking` applies on "all
     scoped capacity released" for every case type except `CHANGE`), which is
     why the case says APPLIED while the refund is still in flight.

   Next: instrument payment between the channel's `SUCCEEDED` response and the
   `RefundSettled` write to find the 7.9 second tail, then decide whether the
   case may apply before its refund step. Both need a window that contains
   refund cases to measure against.

   Measured again at revision 433, with payment's handler phases timed
   (`PaymentInboundEventHandler.submitRefunds` now reports prepare, channel and
   apply milliseconds when the handler exceeds a second):

   - the client's own timeline for a failing refund is: case created, evaluated
     and approved inside 110 milliseconds, ten case polls over four seconds until
     the case is APPLIED, then ten refund polls of one second each; the refund
     settled 70 milliseconds after the tenth poll;
   - payment records `RefundRequested` 5.35 seconds after the client's approval
     and `RefundSettled` 7.78 seconds after that, but **no payment handler run
     exceeded 423 milliseconds** in the whole window, and neither the handler's
     slow-path warning nor any "message stays pending for retry" line was
     logged;
   - so the gap is not one slow handler. Either the first delivery of the
     approval does not settle the refund and a later delivery does, or the
     request is published on one event and settled on another; payment also logs
     "received PostSalesApproved without a payable refund" for many approvals,
     which acknowledges the event without a refund when its allocation is not in
     the payload yet.

   Next: log the refund id with the delivered event id and the handoff status on
   both the request and the settle (not just the total), so the two deliveries
   can be told apart, and check whether post-sales publishes the approval before
   `refund-funding` fills `approvedActions.refunds`.

   Answered by the thread count, not by a slow handler. Payment's handler phases
   were timed (revision 433) and no run exceeded 423 milliseconds while the
   approval still took five seconds to reach a handler: the wait was the
   subscriber's own handler queue, which is sized from `CONSUMER_THREADS`.
   Revision 434 raises it from twelve to thirty two, and in the twelve minutes
   after, **2838 of 2838 refunds settle under a second**. The refund-status and
   case-status groups are gone from the revision 434 window.

4. [ ] The remaining acceptance failures are the post-sales case reaching its
   terminal state a second too late. Measured at revision 435: of 151 cases the
   window reported as "did not reach APPLIED", all 151 are APPLIED, created to
   final within 10.3 seconds at the median and 12.1 at the worst, and the client
   polls for ten. The time is in the cancellation saga, not in post-sales: for
   one case, approval at 08.506, the segment booking CANCELLED at 15.913 (7.4
   seconds) and the case APPLIED at 17.623, while the client's last poll was at
   17.556 -- 67 milliseconds early. No pool, consumer group or shard was
   saturated in that minute.

   Next: remove a hop from that saga. The case applies when it consumes
   `CapacityReleased`, and it could instead read the booking's cancelled state
   when it starts executing -- the same read-the-owner's-state pattern the change
   path already uses -- which is worth about the 1.7 seconds between the
   cancellation and the apply.

   The saga is now traced end to end, and it is four event hops deep:
   the client's approval makes post-sales publish `PostSalesApproved`;
   entitlement-ticketing consumes it and voids the entitlement
   (`apply_subscribed_event` handles `PostSalesApproved`); booking-orchestration
   consumes `EntitlementVoided` and cancels the segment booking with
   `cancellationReason: REFUND`; capacity consumes `SegmentBookingCancelled` and
   releases the hold; post-sales consumes `CapacityReleased` and applies the
   case. At the burst each hop costs one to two seconds, which is the 9.4 to 12.1
   seconds measured, and the client polls for ten.

   The change path already avoids part of this: `ChangeExecutionService` calls
   entitlement-ticketing's void endpoint and booking-orchestration's cancel
   endpoint synchronously rather than waiting for the events to come back.
   Next: decide whether the refund path should do the same, or whether
   post-sales should apply on the booking's cancelled state plus a read of the
   capacity hold instead of on the release event.

   Measured again after entitlement-ticketing gained a logger (it had none, so
   every `log::` call in it -- warnings included -- was discarded) and its
   approval handler was timed where it actually runs
   (`adapters/storage::apply_post_sales_approved`): the handler itself takes at
   most 11 milliseconds, and the gap between the load generator's approve
   request and the handler's log line, matched by trace id, is about **76
   milliseconds**. So the event reaches this service promptly and the 7.2
   seconds measured earlier was specific to that burst minute, not the norm.

5. [x] The offer group's backlog is fixed: the batch path was the unit of
   throughput and its read took 100 entries; with six instances and a read of
   500, the lag on `events:trip-planning` peaks at 3 across a cycle and the
   revision 440 window carries **0 error groups and 0 failed journeys**.

6. [ ] What the revision 438 window showed before that fix: 459163
   requests and 48468 journeys at P99 155 milliseconds with 7 error groups and
   658 failed journeys. The groups, in order: 201 transfer offers refused with
   "No consumed Trip Planning itinerary found", 158 reservation timeouts ("timed
   out waiting for sb"), 120 refund-status and 105 refund case-status, 33
   risk-status, 10 transfer reservation timeouts, 9 disruption refund-status, 6
   invoice basis, 6 change offers, 4 disruption case-status.

   The offer-management consumer is not behind: measured in the burst minute its
   lag was **1** on `events:fare-pricing` and **0** on `events:trip-planning`,
   with 11 and 6 entries pending, and the process used 0.4 of its cores with no
   pool waiting. The client retries an offer twelve times with backoff to five
   seconds, so a refused offer means the itinerary was never consumed in half a
   minute -- the store the offer is built from, not the consumer's pace, is what
   to read next (the transfer group names an itinerary reference; the purchase
   group names a fare quote).

   The refund settle itself held: of the refunds in the twenty minutes around
   that window, 3726 settled under a second, 1889 took one to three, and 32 took
   three to eight -- no eight-second population at all, against 875 before the
   payment thread change. The refund and case-status failures that remain are the
   case applying past the client's poll, the four hop saga above.
3. [ ] Reporting still deadlocks on the event instance: `reporting_business_scopes`
   and `reporting_order_attachments` upserts take the same rows in different
   orders under burst, four times in four minutes after the notification fix.
   Reporting is not in the load generator's request path, so this is lag and
   retries rather than a user-visible failure, but it is the same class of defect
   the notification trigger had.

## The larger burst is the ceiling, measured 2026-09-27

**Closed the same way**, and for the same reason: these are the measurements of
the 8.0-burst envelope that took the level decision. The three items below name
what a burst above the knee costs (reservation confirmation, offer refusals, the
invoice basis race); none of them is reachable at the fixed level, whose windows
are clean, and raising the level again would be a new decision rather than a
backlog item.

The window at revision 440 is clean, and the window after it is not: the load
generator's cycle reaches 2256 to 2822 orders a minute, against the 1000 to 1200
that the shard split and the payment and disruption fixes were measured on. Every
group that returns does so as a consumer queue at that amplitude, and each one is
now measured rather than guessed:

1. [x] Risk assessment. At 2256 and 2822 orders a minute the
   order-created-to-risk-cleared latency went from 0.1 seconds to 3.6 at the mean
   and 13.5 at the ninety-fifth percentile, past the purchase's ten second poll,
   while risk-compliance used 2.9 of its eight cores with no pool waiting and no
   consumer lag. Revision 441 gives it two instances and twelve workers per
   stream; the same latency is now 0.76 at the mean and 2.28 at the ninety-fifth
   percentile, and the risk-status group leaves the window.
2. [x] The refund and change case's last hop. A refund case's booking was
   cancelled at 19:25:37.889 and the case reached APPLIED at 19:25:55.980 --
   eighteen seconds later -- while post-sales' consumer group on
   `events:capacity-availability` stood at 3796 entries of lag. The case applies
   on that stream, so revision 442 gives post-sales three instances and thirty six
   handlers; that lag now peaks at 1424, and the case-status group falls from 607
   in the window to below the offer group.
3. [ ] The reservation confirmation. The revision 442 window's largest group is
   893 staff journeys that failed at `staff-poll-reservation` with "reservation
   and capacity did not confirm". Nothing has measured that path yet: the next
   step is the same one that found the others -- booking-orchestration's and
   capacity-availability's consumer queues and the provider confirmation's own
   latency at the burst minute.
4. [ ] The offer refusals came back at this amplitude: 196 purchase offer
   refusals ("Fare price quote has not been consumed") and 179 transfer ones ("No
   consumed Trip Planning itinerary found") in the revision 442 window, against
   16 in the one before it. The batch change took the trip-planning lag from
   16981 to a peak of 3 at the smaller amplitude, so this is the same queue at a
   bigger burst: measure both streams' lag in the burst minute and size the read
   batch and the replica count again.
5. [ ] The invoice basis race is 114 to 177 per window and is the MISSED_ORDER
   fault the load generator seeds, already documented above.
