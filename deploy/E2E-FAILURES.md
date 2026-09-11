# e2e failure investigation

Working document for the current `make e2e` failures. One entry per root cause,
not per failed assertion: the 52 raw failures in the 2026-09-08 run deduplicate
to roughly a dozen causes, and 12-restart re-runs scripts 01-11 and 14-23
internally so most failures are counted twice.

**Method.** For each cluster, check whether the business outcome was eventually
reached. If it was, the assertion window was too short for the load and the
finding is a *test* defect. If it was not, it is a real defect. This distinction
matters because an earlier pass through these failures mistook a genuine
throughput deficit for a timing artefact — "the order does eventually reach
CONFIRMED" is only reassuring if the backlog that delayed it is bounded, and it
is not.

**Status key.** `OPEN` not yet investigated · `DIAGNOSED` cause established, not
fixed · `FIXED` fixed and verified on the cluster · `TEST-DEFECT` the assertion
was wrong, not the system.

---

## P1 — journey-order consumption deficit (root cause for several clusters)

**Status:** PARTLY FIXED — one of two causes closed

`journey-order` holds **72,281 pending messages** across its 11 streams, and the
backlog is *growing*: 59,377 → 61,469 → 63,325 over two minutes, about +33/s.
That is a sustained throughput deficit, not a queue draining after a restart.
Worst streams: `events:risk-compliance` 18,329, `events:identity-verification`
12,904, `events:booking-orchestration` 9,597, `events:payment` 7,544,
`events:post-sales` 7,004.

Confirmed end to end on `10-account-gate`, which is the cleanest probe because
its assertion already retries 8 × 4s = 32 seconds, far longer than any healthy
projection lag:

- account service reports `status: FROZEN` ✓
- `AccountFrozen` is published on `events:account` and names the account ✓
- journey-order's `account_order_state_snapshots` projection has 27,126 rows but
  **not this account** ✗
- journey-order has **206 pending** on `events:account`, a stream holding only
  200 events — the whole stream is backed up

So a frozen account can still place orders. Real defect, and the same deficit
plausibly explains `order not confirmed (CREATED)`, `no saga found for order`
and the `saga not completed` clusters.

`OrderManagementService.handle` also calls `stateRepository.isEventProcessed`
*before* the type switch, so every ignorable event pays a database read — the
same shape fixed in post-sales. Unlike post-sales this is not the main cost:
`events:risk-compliance` is 100% event types journey-order genuinely handles,
and `events:identity-verification` about 50-60%. Worth fixing, not sufficient.

Affected assertions: `new journey order rejected while frozen`, `frozen
rejection code`, `order not confirmed (CREATED)`, `no saga found for order`,
`saga not completed (...)`, `saga steps not all SUCCEEDED (...)`.

### Cause 1 (FIXED): the handler queue was unbounded

`RedisEventSubscriber` used `Executors.newFixedThreadPool`, whose queue is
unbounded, so the poll loop submitted without limit and the only cap was
`MAX_IN_FLIGHT` at 10,000 against 1-8 handler threads. Messages held an
in-flight slot while queued; Redis' pending timer expired; XAUTOCLAIM
redelivered them; `claimInFlight` dropped each redelivery because the slot was
still held. That is why entries-read advanced at 24/s against a 23/s production
rate while pending still grew at +18.6/s — numbers that cannot both be true
unless something reads and discards.

Fixed with an `ArrayBlockingQueue` sized off the thread count, and a full queue
treated as backpressure. "in-flight bound reached" went from 1,948 occurrences
in a 2,000-line sample to **0**, and the backlog fell from 76,000 to ~31,000.
`RedisEventSubscriberBackpressureTest` pins it; restoring the unbounded queue
makes it fail.

### Cause 2 (OPEN): 148 ms per event

Even with the queue fixed, journey-order processes ~27 events/s across 4
threads — **148 ms each** — against a production rate that keeps pending growing
at about +22/s. Two hypotheses were tested and **both were wrong**, which is
worth recording so they are not retried:

- **`processed_events` bloat was not the cause.** The table had reached
  1,092,388 rows / 205 MB with 1,085,529 rows past their 5-minute retention and
  the oldest 21 hours old, because `OutboxRelay.cleanup()` issued one unbounded
  `DELETE` inside `catch (RuntimeException ignored) {}` — a Seq Scan trying to
  delete a million rows in one transaction, failing, and saying nothing. That is
  a real defect and is fixed (batched deletes, failures logged). But draining the
  table to 8,222 rows and VACUUMing moved per-event cost only from 151 ms to
  148 ms.

- **`listOrders` is not on the hot path.** `journey_order_snapshots` shows
  243,202 sequential scans having read 2.03 **billion** rows, and the cause is
  real: `listOrders` is called with no filter by the loadgen's
  `tail-list-orders` probe, and `WHERE (? = false OR ...)` plus
  `ORDER BY (data->>'createdAt')::timestamptz` plans a Parallel Seq Scan + Sort
  at cost 13,609. But that is an HTTP query path, not the event handler, so it
  explains database load rather than handler latency.

  Worth recording for whoever fixes it: indexing the **text** form of
  `createdAt` drops the plan to an Index Scan at cost 16.9, an 800x improvement
  — but it is **not safe**. The values carry two different fractional precisions
  (59,420 at 9 digits, 56 at 6), so text and timestamp ordering disagree on 12
  rows: comparing `...935231Z` against `...192239945Z`, the `Z` sorts above a
  digit. A `::timestamptz` expression index is rejected as not IMMUTABLE, so the
  real fix is a stored timestamp column, not an index expression.

So the remaining 148 ms is still unexplained. The next step is to measure inside
the handler rather than guessing at the storage layer again.


## P2 — reporting read model never catches up

**Status:** FIXED — three defects in the write path, all measured

`reporting` consumes 37 streams — the widest fan-in in the system — and holds
6,080 pending. Assertions on its read model fail: `reporting stream empty`,
`dash-revenue status unexpected (building)`, `reporting dashboard fact count did
not change`, `reporting waitlist rollup missing`. It was raised to 8 consumer
threads; that evidently did not close the gap.

It was not the P1 rate deficit. Three separate defects in `handle_event`, found
by following a suite stall rather than the pending count:

**1. The materialized views were refreshed on every event.** `handle_event`
called `_refresh_revenue_views` unconditionally, inside its transaction. Each
`REFRESH` takes an ACCESS EXCLUSIVE lock, so concurrent handlers serialised;
`pg_stat_activity` showed three competing `REFRESH reporting_revenue_by_route`
blocking other queries for 68s, and `GET /api/v1/metrics/operational` returned
nothing in 60s — which is what stalled `12-restart.sh` for 20 minutes at
`14-waitlist.sh`. The refresh itself costs 6.3s for `by_route` alone. At the time
of measurement **zero of 26,848 stored rows** had an event type any of the three
views aggregate, so every one of those refreshes was pure waste. Now guarded on
the payment-capture types the views actually filter on.

**2. Rebuilding the aggregator was quadratic.** `MetricAggregator.record`
scanned every event already recorded to reject a duplicate id, and
`_load_aggregator` rebuilds from the whole table on every event. Measured
21.8s per rebuild at 27k rows (0.12s at 2k, 1.91s at 8k — the shape is
unambiguous), on eight consumer threads, which starved the uvicorn workers of
the GIL. Indexing the ids took it to 0.16s. `test_recording_stays_linear_in_the_number_of_events`
pins it; restoring the scan makes it fail.

**3. The aggregator assumed USD.** It holds one currency and rejects any event
that disagrees. `_load_aggregator` used the dataclass default, so every
`RevenueRecognized` raised `metric aggregator currency mismatch: CNY != USD`,
returned TRANSIENT_ERROR and was redelivered forever — 311 occurrences in one
pod's log. This was invisible until the payment fix let captures through at all,
which is the same pattern as the other defects this session surfaced.

Verified after all three: `/api/v1/metrics/operational` answers 200 in ~1.9s
where it previously timed out at 60s; reporting reads **619 events/s** against a
228/s production rate, and its total lag went from **+85/s growing to −69/s
draining**; zero currency-mismatch errors. The residual failures are 23
`OptimisticConcurrencyError: concurrent update detected for dash-revenue`, which
is the OCC retry working as designed on a row eight threads contend for.

## P3 — waitlist never fulfils or expires

**Status:** DIAGNOSED — a genuine gap, not timing

Five assertions, all in one flow: `no journey-order reference appeared on the
waitlist request`, `waitlist did not fulfill (QUEUED)`, `fulfilled waitlist
response missing journey-order reference`, `missing WaitlistFulfilled event`,
plus `waitlist not expired (QUEUED)` and `missing WaitlistExpired event`.

The request stays `QUEUED` because the only event that can advance it is never
published. The chain, all verified:

1. `services/waitlist/src/subscriber.ts` advances a request on
   **`WaitlistCapacityFreed`** only. It deliberately ignores `CapacityReleased`
   -- that fact carries no `segmentRef`/`departureDate`, so `tryParseCapacityFreed`
   returns undefined and the handler acks without doing anything. The comment
   there says so explicitly.
2. capacity-availability emits `WaitlistCapacityFreed` (domain.rs:950, :984) only
   when `self.waitlist_state.is_active()` and capacity actually increased.
3. `waitlist_state` becomes Active only in one place (domain.rs:813): when a hold
   request finds `occupied >= effective_capacity` and therefore FAILS.

So the waitlist has to be activated by a hold that was rejected for being full,
before any release can free it. On the live cluster **`WaitlistActivated` appears
0 times in the last 500 capacity events** -- the state machine never enters
Active, so nothing can ever leave QUEUED.

waitlist itself is healthy: 1 pending message, no errors. This is not throughput
and not a test window (the assertion polls 24 x 5s = 120s).

Two candidate resolutions, not yet chosen:
- the test does not fill capacity before queueing, so activation never triggers
  -- making it a test-setup gap; or
- releasing capacity on a segment that has queued requests should free them
  regardless of whether a prior hold was rejected, making the `is_active()` guard
  too narrow.

Deciding needs the intended semantics from the capacity/waitlist contracts, which
is why this is left diagnosed rather than guessed at.

## P4 — managed refund fee uses the wrong penalty base

**Status:** FIXED — test defect, mine

`managed refund_fee not applied (penalty base 500, expected 9000;
tier=TIER_GT_15_DAYS)`. The tier is now correct — `TIER_GT_15_DAYS`, which was
the point of the earlier policy-context fix — but the penalty base is 500 where
the test expects 9000. 500 is 5% of 10000, i.e. the *penalty amount* for that
tier, so this looks like the assertion reading the penalty where it wants the
base, or the response exposing one under the other's name. Verify against the
contract before changing either side; the same test previously carried a stale
8750 expectation that only passed while refunds were broken.

Independent of throughput, and it was a defect in the assertion I wrote earlier,
not in the service. The variable was named `PENALTY_BASE` while reading
`penaltyAmount` — the fee the tier charges (5% of the base), not the base. The
base is `BASE_FARE.originalAmount`, and it is exposed by the API under
`refundAssessment.components`, NOT the `componentDecisions` name the persistence
layer uses; the mapper renames it. Located by `componentType` rather than array
index, since the ordering is not contractual.

Verified: reading the right field made the assertion meaningful, and it then
exposed a SECOND defect underneath -- `BASE_FARE originalAmount 10000, expected
9000`. The field is now correct; the value is not, and that one is in the service.

**journey-order hardcodes the fare.** `OrderManagementService.createOrder` builds
its order items with `Money.of("CNY", "100.00")` (line ~130) and never reads the
offer's actual total. 07-fare-rules publishes a rule set with base_fare 12000 and
asserts the offer total IS 12000 -- that passes -- but the order it then creates
carries 100.00 regardless. That is why every `post_sales_policy_contexts` row on
the cluster holds 100.00 or 200.00 and none holds 120.00.

The existing comment at line 112 admits the shape of it ("journey-order does not
yet resolve the real offer itinerary"), and the fare is the same gap: the service
receives only `offerId`/`offerVersion` and does not call offer-management for the
priced total. Fixing it means adding that lookup, which is a real cross-service
change rather than a test adjustment, so it is recorded here rather than
attempted alongside the rest.

Consequence worth stating: an order's monetary summary does not reflect what the
customer was quoted. Every refund is therefore priced off 100.00 no matter what
the fare rules say -- the penalty percentages are applied correctly to the wrong
base.

### Third layer: the managed refund_fee is still not deducted

**Status:** OPEN — service defect, diagnosed

With the fare lookup in place the base is now the real offer total: the assertion
reads `BASE_FARE originalAmount 12000` where it read 10000 before. So the order
carries what the customer was quoted, and the first two layers are closed.

The remaining 3000 is the managed `refund_fee`. fare-pricing does compute it --
`assess_refund` in `services/fare-pricing/src/fare_pricing/domain.py` returns
`refundableAmount = total - non_refundable - fee`, i.e. 9000 for this rule set --
and post-sales does fetch it. `PostSalesApplicationService.decisionFor` then
discards it: `penaltyBase` is `policyContext.originalFareOr(...)`, which uses the
quote's `refundableAmount` only as the fallback when no policy context exists.
Now that P5 guarantees a context for every refund, that fallback is never taken,
so the fee never reaches the waterfall.

This is not the fare bug again and predates this session's work (the expression is
from `df5fb9c8`). It is a semantics question the contracts have to answer: whether
the penalty base is the order fare or fare-pricing's fee-adjusted refundable. The
two sources disagree by exactly the managed fee, and only one of them can be
authoritative -- which is why this is recorded rather than picked.

## P5 — post-sales case applied but order not adjusted

**Status:** OPEN

`03-refund`: `case not applied (APPROVED)` and `order not adjusted (CREATED)`,
followed by the downstream `no NotificationScheduled for traveler`, `no
ReconciliationCompleted mentioning order`, `no 95.00 revenue reversal`. The case
reaches APPROVED and stops before APPLIED, so the apply step or its consumer is
the break. `PostSalesApplied` is what journey-order consumes to adjust the
order, so this may be downstream of P1 — needs checking rather than assuming.

## P6 — risk-blocked order not cancelled

**Status:** OPEN

`06-risk`: `blocked order not CANCELLED (CREATED)`. journey-order handles
`RiskBlockApplied`, and `events:risk-compliance` is journey-order's largest
backlog at 18,329 — likely P1, but the causal link needs to be shown, not
assumed.

## P7 — seat release leaves the seat STANDING

**Status:** FIXED — 20-seat now 20/20

Three defects stacked, and the outer two hid the inner one. seat-assignment
matches a CapacityReleased to its allocations on
`(capacityHoldId, capacityUnitRef, fromSeq, toSeq)`; a non-match returns an empty
list and `transitionAllocationsByCapacityRecovery` treats that as nothing-to-do,
with no log.

1. capacity-availability returned `capacityUnitRef` under the name `classRef`.
   The value was a seat number like "09D"; a class ref is "standard", and is
   empty on these holds.
2. Neither the POST nor the GET exposed `interval`, so a caller could not record
   an allocation the release would find. The GET does now; the POST keeps its
   four documented fields.
3. 20-seat invented both values (`"cap-standard"`, interval 1..3) even against a
   real hold that had granted "05B" over 0..1.

The API contract documented the GET as "Full hold details" with no field list;
it now has one.

## P8 — disruption manual-review path broken

**Status:** RESOLVED by the P1 fix — 17-disruption now 0 failures

Six assertions in `17-disruption`, all in the manual-review branch. All six were
one cascade: `select_option_type` used `next()` with no default, so when the
MANUAL option was absent it raised StopIteration, printed a traceback into the
run, left `OPT` empty, and POSTed an empty optionId — reported as `select manual
[got 422]`, with the following four assertions failing behind it.

The absent option was downstream of P1: the case had not progressed far enough
for options to be generated, because journey-order had not consumed the events
that drive it. With P1 fixed the script passes 85/85.

The selector was still changed to report what it found (`recovery case X offers
no MANUAL option (AVAILABLE:REFUND,WAIT)`) instead of failing as a 422 four
steps later. A cascade of five failures that all trace to one missing value is
exactly the shape that made this list look longer than it was.

## P9 — customer-service and notification timeline facts missing

**Status:** OPEN

`08-notify-support`: `timeline missing PostSalesApplied refund fact`.
`09-manual-action`: `customer-service timeline records
ManualActionResultRecorded`. Both are timeline projections fed by events; check
against P1/P2 before treating as separate.

## P10 — snapshot row counts change across a full restart

**Status:** OPEN

`12-restart`: `snapshot row counts changed across restart`. This is the
persistence certification, and it is the one failure that would be serious even
if everything else were timing. Note the test compares counts taken before and
after deleting every pod while the loadgen is paused — but the loadgen pause
does not stop in-flight consumers, so a backlog still draining during the
snapshot could legitimately change counts. Determine which it is before
concluding data was lost.

---

## Not a defect

- **`scalper:failed`, `purchase:abandoned`, `ride:no_show`, post-sales 409
  `REFUND_ALREADY_IN_PROGRESS`** in the loadgen are deliberate simulation. The
  config says so: `# funnel abandonment (customer walks away without error)`
  with `p_abandon_before_payment: 0.10`. The 14,584 sagas in FAILED with
  `terminalReason: "payment failed"` are these — steps `risk-check`, `reserve`
  and `seat-assign` all SUCCEEDED, stopping at `invoice` because nobody paid.
