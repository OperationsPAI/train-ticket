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

**Status:** OPEN

`reporting` consumes 37 streams — the widest fan-in in the system — and holds
6,080 pending. Assertions on its read model fail: `reporting stream empty`,
`dash-revenue status unexpected (building)`, `reporting dashboard fact count did
not change`, `reporting waitlist rollup missing`. It was raised to 8 consumer
threads; that evidently did not close the gap.

Need to determine whether this is the same deficit as P1 (rate) or something
structural in the dashboard rebuild.

## P3 — waitlist never fulfils or expires

**Status:** OPEN

Five assertions, all in one flow: `no journey-order reference appeared on the
waitlist request`, `waitlist did not fulfill (QUEUED)`, `fulfilled waitlist
response missing journey-order reference`, `missing WaitlistFulfilled event`,
plus `waitlist not expired (QUEUED)` and `missing WaitlistExpired event`.

The request stays `QUEUED`, which means neither the fulfilment path nor the
expiry path advances it. `deploy/e2e/12-restart.sh` sees the same, so it is not
specific to a fresh cluster. Check whether the promotion is driven by an event
journey-order must publish (which would make it downstream of P1) or by a
waitlist-internal timer.

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

Verified: `managed refund_fee applied: penalty base = 90.00 CNY (120.00 -
30.00)` now passes, 29/31 in 07-fare-rules. The two remaining failures there are
`no saga for managed-rule order` — P1.

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

**Status:** OPEN

`20-seat`: `release status STANDING`. seat-assignment is a Go service with a
small backlog, so this is probably its own logic rather than throughput.

## P8 — disruption manual-review path broken

**Status:** OPEN

Six assertions in `17-disruption`, all in the manual-review branch: `select
manual`, `not manual review`, `manual close got N`, `resolve manual`, `close
manual recovered`, `manual close status`. This whole branch passed in an earlier
run (17-disruption was 0 failures in e2e4), so it is worth checking what changed
rather than treating it as long-standing.

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
