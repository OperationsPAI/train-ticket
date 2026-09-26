# Current project status

Last verified: 2026-09-27, Asia/Beijing.

## Runtime

The resident Helm release is `train-ticket` in namespace `train-ticket`,
revision **451**.
Its values come from one file, `deploy/helm/train-ticket/values.yaml`: the
registry, the `local-path` storage class, the central telemetry store, the
resident exporters, the load generator settings and the `loadgen-resident-state`
claim all live there. The former cluster, case, resident, production and ACR
overlays are deleted.

PostgreSQL is one instance per workload shape, not one instance for everything:
`core`, `hot`, `txn`, `read`, `user` and `event`, each with its own databases,
resources and `pgInstance` mapping. Every service's database is named in
`postgres.instances.<shard>.databases`; a missing entry is a service that
starts, connects, and fails on its first query.
Redis Streams carries events, with no persistent volume or AOF, so Redis can be
connected to without any event having been consumed.

The resident Go load generator uses the `loadgen-resident-state` claim, a 600
second diurnal period, `target_rps: 20`, bursts between three and eight times
the mean rate and a 10 second HTTP request timeout. It is scaled to zero
between acceptance runs; the run whose evidence is below was made by scaling it
to one and back.

## Implemented and deployed

| Area | Current behaviour |
|---|---|
| Business telemetry | Every source metric in the [business metric contract](07-observability/business-metrics.md) is implemented and deployed: order funnel, booking saga, payment and its conservation invariant, capacity and seat levels, ticketing lifecycle, waitlist, wallet, the notification delivery queue and the Redis transport itself. |
| Transport reporter | `redis-state-collector` reads consumer lag, pending entries, the oldest owed entry and the dead-letter population for every stream, once, and exports them over the same OTLP gateway the services use. |
| Notification delivery policy | Stale and superseded notifications expire instead of being delivered late: 30 minutes for action-required, 6 hours for order state and operational, 72 hours for financial, 24 hours otherwise. A terminal state in the same TTL window supersedes an earlier one. |
| Notification queue depth | Maintained by a trigger on the delivery queue and published as a gauge, with the oldest due task's age. Counting the queue itself is a scan of tens of millions of rows, which is why the trigger exists. |
| Disruption recovery | A segment signal with no affected order is an audited no-op that is acknowledged; only resolver and database failures retry. |
| Autoscaled replicas | The chart writes `spec.replicas` only on install for a Deployment that has an HPA, so an upgrade no longer resets a scaled-out replica count. |
| Redis consumer recovery | A failed pending-entry recovery no longer ends consumption in any language kit; `XAUTOCLAIM` cursors are paged rather than restarted. |
| Trip planning | Search reads supply on demand rather than loading every service plan at startup; a new pod is ready in seconds and holds about 3Mi. |
| Reporting | Monetary revenue fields are gross `PaymentCaptured` amounts, and the summary views are committed rather than aggregated per request. |
| Database sharding | Six shards by workload shape, with the copies and cutover checks recorded in the chart. |
| Standing policy | Quota is ten percent of assignable second class supply, rounded down, and standing has no physical SeatUnit. |
| Service quality compensation | An approved `COMPENSATION` issues the approved currency and amount to the order account as wallet credit, preserving the original tickets, seats, order and payment. |

## Verification evidence

| Check | Result | Evidence |
|---|---|---|
| Ten minute window at revision 451, two consecutive cycles | 342783 requests, 37846 journeys, P99 294 milliseconds, **0 unexpected error groups and 0 failed journeys**; the cycle after it, 23:09 to 23:19, is clean as well | `.scratch/runtime-fixes/goal-rev451-clean-window.json` |
| Ten minute window at revision 447, the same burst, one fix later | 439115 requests, 48526 journeys, P99 106 milliseconds, 0 error groups, 20 failed journeys at the refund case poll (see the reservation and refund sections below) | `.scratch/runtime-fixes/goal-rev446-ten-minute.json` |
| Ten minute window at revision 445, larger bursts | 384403 requests, 43046 journeys, P99 107 milliseconds, 1 error group, 61 failed journeys: 40 invoice basis, 21 transfer delay reports | `.scratch/runtime-fixes/goal-rev445-ten-minute.json` |
| Ten minute window at revision 444, larger bursts | 641380 requests, 61102 journeys, P99 254 milliseconds, 2 error groups, 5345 failed journeys: 2305 staff reservation polls, ~1200 post-sales case polls, ~1100 offer refusals | `.scratch/runtime-fixes/goal-rev444-ten-minute.json` |
| Ten minute window at revision 442, larger bursts | 366745 requests, 38432 journeys, P99 119 milliseconds, 4 error groups, 1945 failed journeys: 893 staff reservation polls ("reservation and capacity did not confirm"), 196 purchase and 179 transfer offer refusals, 114 invoice basis, 17 booking saga | `.scratch/runtime-fixes/goal-rev442-ten-minute.json` |
| Ten minute window at revision 441, larger bursts | 303892 requests, 32699 journeys, P99 182 milliseconds, 2 error groups, 729 failed journeys: 607 post-sales case-status, 177 invoice basis, 16 transfer offer | `.scratch/runtime-fixes/goal-rev441-ten-minute.json` |
| Ten minute window at revision 440 | 399599 requests, 45861 journeys, P99 112 milliseconds, **0 unexpected error groups and 0 failed journeys** | `.scratch/runtime-fixes/goal-rev440-ten-minute.json` |
| Ten minute window at revision 440 | 160703 requests, 18366 journeys, P99 112 milliseconds, **0 unexpected error groups and 0 failed journeys**; over the two cycles together, 18:17 to 18:39 carries the peaks of both (7599 and 8758 journeys a minute) with no failure at all | `.scratch/runtime-fixes/goal-rev440b-ten-minute.json` |
| Ten minute window at revision 438 | 459163 requests, 48468 journeys, P99 155 milliseconds, 7 error groups, 658 failed journeys -- 209 offer refusals (quote not consumed), 101 booking saga lookups timing out, the rest the invoice basis race and a few staff steps | `.scratch/runtime-fixes/goal-rev438b-ten-minute.json` |
| Resident cycle on revision 377 | 450756 requests and 50345 journeys in 600 seconds, P99 1218 milliseconds, **12211 failed journeys** | `.scratch/runtime-fixes/goal-rev377-ten-minute.json` |
| Resident cycle on revision 386 | 455602 requests and 96165 journeys in 600 seconds, **19569 failed journeys**, dominated by Trip Planning search | `.scratch/runtime-fixes/goal-rev386-ten-minute.json` |
| Resident window on revision 390 | 488382 requests and 64690 journeys in 600 seconds, **10033 failed journeys** | `.scratch/runtime-fixes/goal-rev388-ten-minute.json` |
| Resident window on revision 391 | 173869 requests and 61293 journeys, **1046 failed journeys** (1.7%), no failed reservations at all in the shop | `.scratch/runtime-fixes/goal-rev391-ten-minute.json` |
| Resident window on revision 393 | 413245 requests and 64300 journeys, P99 789 milliseconds, **5673 failed journeys** (8.8%), all of them reservations that did not confirm | `.scratch/runtime-fixes/goal-rev393-ten-minute.json` |
| Resident window on revision 394 | 441056 requests and 87788 journeys, P99 761 milliseconds, **11814 failed journeys**, the same shape | `.scratch/runtime-fixes/goal-rev394-ten-minute.json` |
| Resident window on a reset environment | 402375 requests and 42270 journeys, **P99 167 milliseconds**, ten request errors in the whole window, **4151 failed journeys** | `.scratch/runtime-fixes/goal-fresh-ten-minute.json` |
| Resident window on revision 399 | 524002 requests and 53803 journeys, P99 843 milliseconds, **4142 failed journeys** (7.7%), two request error groups of 21 and 11 | `.scratch/runtime-fixes/goal-rev399-ten-minute.json` |
| Resident window on revision 401 | 514784 requests and 56315 journeys, P99 1093 milliseconds, **8859 failed journeys** (15.7%), no request error groups | `.scratch/runtime-fixes/goal-rev401-ten-minute.json` |
| Resident window on revision 417 | 679108 requests and 66765 journeys, **P99 219 milliseconds**, **4208 failed journeys** (6.3%), two request error groups | `.scratch/runtime-fixes/goal-rev417-ten-minute.json` |
| Resident window on revision 418 | 434385 requests and 49408 journeys, **P99 129 milliseconds**, **1640 failed journeys** (3.3%), no request error groups, no `offer` failure | `.scratch/runtime-fixes/goal-rev418-ten-minute.json` |
| Resident window on revision 419 | 328794 requests and 75834 journeys, **P99 125 milliseconds**, **223 failed journeys** (0.29%), one request error group | `.scratch/runtime-fixes/goal-rev419-ten-minute.json` |
| Resident window on revision 421 | 424100 requests and 86796 journeys, **P99 154 milliseconds**, **177 failed journeys** (0.20%), one request error group | `.scratch/runtime-fixes/goal-rev421-ten-minute.json` |
| Business metric contract | 22 chart tests pass, including the label allowlist and the recording rule targets | `deploy/tests/test_observability_metrics_config.py` |
| Transport collector | Unit and `miniredis` tests pass; label sets, entry-id ages, dead-letter attribution and the no-poll-before-first-read case | `tools/redis-state-collector` |
| Notification module suite | 108 passed, 6 skipped, 0 failures | `services/notification` `npm test` |
| Booking orchestration module suite | 108 passed, 11 skipped, 0 failures | `services/booking-orchestration` |
| Journey order module suite | 88 passed, 13 skipped, 0 failures | `services/journey-order` |
| Payment and post sales module suites | 78 and 118 tests, 0 failures | `services/payment`, `services/post-sales` |
| Capacity availability and entitlement ticketing | 41 and 36 tests, 0 failures | `services/capacity-availability`, `services/entitlement-ticketing` |
| Wallet promotion module suite | 41 passed, 4 skipped, 0 failures | `services/wallet-promotion` |
| Waitlist module suite | 99 passed, 5 skipped, 0 failures | `services/waitlist` |
| Go service suites | All packages pass | `services/fulfillment`, `services/seat-assignment`, `services/payment-channel` |

The skipped tests are the ones gated on `TEST_DATABASE_URL` and on other
environment variables that a module run does not set. They are not a claim that
the live path works.

### Duplicate publication is fixed

Every replica ran its own outbox relay over the same table, and the relay
publishes before it marks rows published, so two replicas that had both selected
the same unpublished rows published them both. The duplicate share is the
relay's work time over its poll interval, and measured at revision 399 it was
**38 percent** of `events:booking-orchestration`, **40 percent** of
`events:journey-order`, **43 percent** of `events:post-sales` and **74 percent**
of `events:capacity-availability` -- every consumer group on those streams read,
parsed, deduplicated and acknowledged a second copy of that share.

The Java and Rust platform kits now elect one publisher per database with a
session advisory lock; the Go kit's `DBTX` interface offers no session to hold
one, so it still relays from every replica, which its 250 millisecond interval
bounds at the 2 to 4 percent measured on `events:provider-integration` and
`events:seat-assignment`. Measured at revision 401, all four streams carry zero
duplicates.

Two earlier reports go with this. The Rust relay opened and discarded its
leadership connection on every poll, which at six replicas and a fifty
millisecond interval sent hundreds of new backends a second at Postgres; a
reservation that had reached its Hold inside 0.2 seconds at the ninety-fifth
percentile took ninety. And the reservation request needed its *own* connection
held rather than a pooled one, because a pooled connection that goes back to the
pool keeps its session and its lock.

### The reservation poll stopped failing, and what was left behind it

At revision 444 -- the raised burst, where the cycle peak carries about fourteen
thousand journeys a minute against the nine thousand the earlier window held --
the reservation poll was again the largest group: 2305 purchases and transfers
whose segment booking did not confirm inside the client's ten polls at one
second, plus about 1100 offer refusals and 1200 post-sales case polls in the
same peak minutes.

The reservation confirm is a chain, and the number that matters is the time
from the client's request to `CONFIRMED`. Off the peak it is **0.3 seconds**; at
the peak it was **6 to 12 seconds**. One purchase that failed at
`staff-poll-reservation` asked for its booking at `20:52:02.824` and the booking
and its capacity hold both reached `CONFIRMED` at `20:52:14.5`, about 1.2
seconds after the client's last poll. The slow link was `provider-integration`:
it was the only group on `events:booking-orchestration` behind at the peak, with
the oldest undelivered entry 5 to 7 seconds old while `capacity-availability`
and `entitlement-ticketing` on the same stream sat at zero.

That one fix -- twenty four loops across three replicas instead of sixteen
across two -- takes the same window from 5345 failed journeys to **61**. Broken
down, the 61 were 40 invoice amount bases and 21 transfer delay reports, so the
two remaining groups were the ones to read next.

### The invoice basis and the delay report were both a late reader

The invoice 404 is not a missing projection: the order had its `amounts`,
`amount_history` and `orders` rows, and `finance-settlement` had recognised its
revenue at the payment's own timestamp. It is a *late* one. `finance-settlement`
holds one handler pool across the seven streams it reads, and at the peak the
booking stream floods it: this group's lag on
`events:booking-orchestration` grew from 1814 to 26460 and its oldest undelivered
entry from 10 to 150 seconds across four peak minutes, while the payment stream's
group stayed at zero, the pod sat at a quarter of a core and the connection pool
had no queue. A second replica takes that window to zero invoice failures.

The transfer delay report has the same shape one layer down:
`transfer-management` read its three streams through one background reader, so
the trip planning flood starved the fulfillment report the connection status
waits on -- 127 seconds behind on `events:trip-planning` against 12 on
`events:fulfillment`. One reader per stream per worker, the same change
`disruption-recovery` already carries, takes that group to zero as well.

### The remaining failure is the refund's first hop

The window at revision 447 carries **0 request error groups and 20 failed
journeys out of 48526**. Every one of the twenty is a refund or disruption case
that did not reach `APPLIED` inside the client's poll, all inside the peak
minute and each about 0.2 to 1.2 seconds past it.

The hop is `entitlement-ticketing`. A case created at `21:44:14.13` was approved
and answered 200 at `21:44:14.21`, and the entitlements were voided at
`21:44:22.10` -- 7.9 seconds later, with the pod at a third of a core. The case
reached `APPLIED` at `21:44:24.46`, after the client's last poll at
`21:44:23.26`. Twelve loops across three replicas replace eight across two.

Measured across the window, a refund's time from case creation to `APPLIED` is
0.5 seconds at the median off the peak, 3.8 at the median of the peak minute and
8.4 at its ninety-fifth percentile, against a ten second poll budget.

#### The eight-times burst is a second, larger envelope

The cycle's bursts are not one size. The windows above caught a four-times
burst; the one at `22:13` on 2026-09-27 caught an eight-times burst --
**12091 journeys in the peak minute against 7591** -- and it fails again, with
2061 reservation polls and about 3000 post-sales and refund polls across the two
peak minutes. What it proves is that the fixes above are correct and not yet
sufficient: the same hops are the late ones, at a larger rate.

The two measurements that decide where the next capacity goes:

1. Capacity's **admission** is not the wait at any amplitude: a booking reached
   its hold 0.2 to 0.3 seconds after it was requested in the peak minute. The
   hold's **confirmation** was 12.0 seconds at the median and 16.9 at the
   ninety-fifth percentile, and `booking-orchestration`'s oldest undelivered
   entry on `events:capacity-availability` was 10.8 seconds old at the same
   moment. A third booking replica is deployed for that leg.
2. `payment`'s group on `events:booking-orchestration` grew from 4.5 to 107
   seconds of oldest undelivered entry in the same four minutes while the pod
   used a fifth of a core, and the window answered `refund did not reach
   SETTLED` 406 times. A second payment replica is deployed for it.

What the eight-times burst says about the refund itself is that no single hop is
left to name: a case that fails now spends 9 to 12 seconds spread across four
or five hops that are each one to four seconds late, against a ten second poll.
Cutting it inside the budget is a throughput question across the whole chain,
not one more consumer count.

#### The configured envelope is wider than any window has measured

`loadgen-config.yaml` offers `20` journeys a second before shaping, a diurnal
multiplier of `4.0` at its peak and a burst multiplier of up to `8.0`, so the
envelope's arithmetic ceiling is **640 journeys a second** -- 38400 in a minute.
The largest peak minute any window has caught is **16074 journeys**, and the
cycles that follow a restart settle near 7500. The multiplier is drawn per
burst, so what a window measures is one draw, not the ceiling.

That is the shape of the remaining risk: a window can be clean at the burst it
caught and say nothing about the draw it did not. The honest statement at
revision 451 is that the two consecutive cycles below the largest observed burst
are clean, and that no window has yet run at the arithmetic ceiling.

#### What the largest burst asked for, and what it got

The window that did catch the eight-times draw named six groups whose own pace,
not their dependencies, was short: every one had its pod at a fraction of its
cores and its pool with nothing waiting. Their consumer counts and handler
counts are raised in one revision: `offer-management` to nine replicas,
`journey-order` to three, `traveler-profile` to two of sixteen, `fare-pricing`
to three, `identity-verification` to two and `finance-settlement` to three.

### The resident cycle is not yet clean

The cycle does not pass the acceptance in [TODO](TODO.md#acceptance) yet, but
the shape of what fails has changed, and it swings: the same revision has
recorded a window with 1046 failed journeys and one with 5673. The last measured
window, at revision 401, carries **8859 failed journeys out of 56315**, and 8011
of them are reservations and transfers that did not confirm inside the client's
ten second poll. They are not spread evenly: the low minutes of the envelope
fail almost nothing, and the burst minutes fail most of the window, so what is
left is a capacity limit rather than a broken path. The two named defects of the
previous pass -- a change whose replacement reservation is refused as a double
booking, and a disruption conflict reported as a 503 -- are fixed and their
groups are down to a handful.

What the measurements say is left is one instance doing the whole system's work.
The chart defines six PostgreSQL shards, but only six of the thirty-seven
services set `pgInstance`; the other thirty-one use `core`, and the read shard's
copies of the read-heavy databases hold no tables at all. Measured over one
25 second window on `core`:

| Database | Transactions | Blocks read | Tuples fetched |
|---|---|---|---|
| `fare_pricing` | 20886 | 222937 | 22348 |
| `trip_planning` | 12304 | 827143 | 45420629 |
| `risk_compliance` | 10906 | 34071 | 35435 |
| `seat_assignment` | 9712 | 987971 | 793872 |
| `post_sales` | 9057 | 100400 | 33054 |
| `capacity_availability` | 7798 | 529477 | 858664 |

`trip_planning` alone reads 45 million tuples in that window: 56 searches a
second, each fetching about 20000 segment rows from the plan tables. The peak
windows inside this run, taken before the instance reached that state, carried
**zero failed journeys out of 10670** and 36 out of 8924, so the reservation
path itself holds when the instance has room.

The environment was then reset rather than migrated, which is what the owner
asked for: every database was dropped and recreated empty, the load generator's
persistent registry and recordings were deleted, the release was redeployed so
each service ran its migrations against the fresh schema, and the reference data
was re-seeded. That removed the legacy rows the migration existed to carry, and
it took the request tail with it: P99 fell from 761 to **167 milliseconds** and
the whole ten minute window carried ten request errors. The journey failures
that remain are one flow problem rather than a load problem -- 4151 of 42270 --
and they are traced in the backlog.

Trip Planning and Seat Assignment had already been cut over to the read shard --
the first by a verified copy, the second by logical replication so core kept
serving -- which takes both of those readers off the instance that carries
everything else. Within the reservation itself, capacity is where the time now
goes, and the measurement names the mechanism: **21 of the 24 active sessions on
`capacity_availability` were waiting on
`pg_advisory_xact_lock(hashtextextended(...))`**, waits of 1.0 to 2.9 seconds
were logged as slow statements, and a reservation reached its capacity Hold 85
to 112 seconds after it was requested at the ninety-fifth percentile. Every
admission into one `pool:segment:class` takes that lock for its whole
transaction, and the load generator reuses a small pool set, so admissions
queue. Capacity's consumer group is not the constraint: it sits at a few hundred
entries and keeps up with the stream.

The provider leg is not the constraint either, in the minutes capacity is
keeping up: a reservation reaches its provider confirmation 0.04 to 0.12 seconds
after it is requested at the ninety-fifth percentile, and the provider
confirmation reaches the booking 0.1 to 0.9 seconds after that. The replay fix
that made a repeated request answer with the stored reservation is what took
that leg off the critical path.

#### What the reservation path cost before those fixes

Revision 377 failed 12211 of 50345 journeys, and they were not a spread of
unrelated defects:

| Group | Count in the window | Cause |
|---|---|---|
| `purchase` `failed_at_staff-poll-reservation` | 7694 | The segment booking reaches `CONFIRMED` without a capacity Hold: 2586 of the 2627 reservations confirmed in the five minutes around the window had no `capacityHoldId`. |
| `refund`/`change` `failed_at_case-status` | 2521 | The post sales case does not reach `APPLIED` inside the client's poll budget, which follows the reservation it waits on. |
| `change` `failed_at_case-evaluate` | 1089 | Post Sales answers 500 `IllegalStateException: target offer travelers differ from the original order`. |
| `change` `failed_at_case-approve` | 367 | The decision quote had expired before approval. |
| `disruption` `failed_at_disruption-select` | 245 | 503 `Downstream service unavailable`. |

The first three are one problem. Capacity's consumer group on
`events:booking-orchestration` sat three to five thousand entries behind
throughout the window, with the oldest pending entry minutes old, while the load
generator polls a reservation for ten attempts at one second. A reservation
that confirms two minutes later has already been given up on, and the post sales
and change cases that wait on it fail with it.

Three defects were fixed on the way to this measurement and are deployed:
a reservation whose cached supply facts predated the purchase source failed
with "unsupported purchase source" and consumed five delivery attempts before
the dead letter stream (19102 retries and 3899 dead letters in twenty minutes);
an unresolvable historical hold failed every admission into the pool it
occupied; and the capacity level rebuild loaded seven gigabytes of pool
documents into memory and was OOM-killed at its four gigabyte limit. What
remains is the throughput of admission itself, which is the open item below.

#### What this pass fixed

Each of these was measured, reproduced, fixed and deployed, one commit each:

| Defect | Evidence before the fix |
|---|---|
| Capacity rejected a reservation whose cached supply facts predated the purchase source. | 19102 retries and 3899 dead letters in twenty minutes. |
| A hold whose unit was in more than one replacement lineage failed every admission into its pool. | 989 retries in three minutes. |
| A hold and its replacement allocation legitimately disagreed about the unit, and the admission treated that as corruption. | 3458 failures in three minutes. |
| A standing hold, then a standing allocation, named a quota slot the current policy no longer had. | 1918 and 1829 retries in five minutes. |
| Substituting a unit to keep an admission going broke the check that compares the availability fold with the admission. | 221 failures in five minutes. |
| Post Sales compared a change's target offer with every traveler on the order instead of the case's scope. | 1637 evaluate calls answered 500 in one window. |
| A segment booking was confirmed by whichever of the provider confirmation and the capacity Hold arrived first. | 2586 of 2627 confirmed reservations had no `capacityHoldId`. |
| Trip Planning's search read the heap for the columns it selects, at 1366 milliseconds per search. | 14425 searches answered 503 in one window; 130-210 milliseconds after a covering index. |
| 457609 live holds, 17.6% of the table, had no cached reservation facts, so the admission's occupancy fold never saw them and handed out the seats they occupied. | 2739 `OVERLAPPING_HOLD` failures in four minutes. Backfilled from the pool each hold belongs to; no confirmed hold is missing its facts now. |
| The fold counted a hold's replacement allocation instead of the unit the pool aggregate refuses on. | The same 2739 refusals, each one a purchase the user lost. |
| A change's replacement reservation was refused as a double booking while the ticket it replaces still held the seat, for the same traveler. | 4801 failed journeys in one minute; 1910 change executions in a window. |

### Previously recorded windows

These windows cover earlier revisions and are kept for context; the current
revision's evidence is the cycle recorded above.

| Check | Result | Evidence |
|---|---|---|
| Ten minute window at revision 435 | 560646 requests, 62692 journeys, P99 119 milliseconds, 0 unexpected error groups, 152 failed journeys -- 138 post-sales case-status and 13 disruption case-status, all of them a case that reaches its terminal state within two seconds of the last poll | `.scratch/runtime-fixes/goal-rev435-ten-minute.json` |
| Ten minute window at revision 434 | 510005 requests, 57127 journeys, P99 115 milliseconds, 1 unexpected error group (`invoice-amount-basis` 404 x2), 78 failed journeys: 76 disruption recovery and 2 invoice. The refund and change case-status groups are gone | `.scratch/runtime-fixes/goal-rev434-ten-minute.json` |
| Ten minute window at revision 431, with the cycle's burst | 607568 requests, 64894 journeys, P99 128 milliseconds, 1 unexpected error group (`invoice-amount-basis` 404 x3), 989 failed journeys -- every one a post-sales or recovery poll tail, none a timeout | `.scratch/runtime-fixes/goal-rev431d-ten-minute.json` |
| Ten minute window at revision 430, the cycle's burst | 164039 requests, 18571 journeys, P99 106 milliseconds, 0 unexpected error groups, 24 failed journeys (all the disruption recovery tail) | `.scratch/runtime-fixes/goal-rev430-ten-minute.json` |
| Ten minute window at revision 430 | 66662 journeys, P99 137 milliseconds across every service, 0 unexpected error groups, 253 failed journeys (refund, change and disruption poll tails; no timeout, no 503) | `.scratch/runtime-fixes/goal-rev430b-ten-minute.json` |
| Ten minute window at revision 427, first cycle after the shard split | 424214 requests, 47671 journeys, P99 118 milliseconds, 0 unexpected error groups, 0 failed journeys | `.scratch/runtime-fixes/goal-rev427b-ten-minute.json` |
| Ten minute window at revision 427, the cycle's burst | 70691 journeys, P99 10000 milliseconds, 4194 failed: fare-pricing quotes at the client's 10 second timeout, journey-order 503s, post-sales timeouts | `.scratch/runtime-fixes/goal-rev427c-ten-minute.json` |
| Ten minute window at revision 425 | 429649 requests, 84282 journeys, P99 128 milliseconds, 1 unexpected error group (`invoice-amount-basis` 404 x22), 159 failed journeys | `.scratch/runtime-fixes/goal-rev425-hardcode-ten-minute.json` |
| Ten minute window at revision 426 | 466963 requests, 51607 journeys, P99 2996 milliseconds, 7 unexpected error groups, 1407 failed journeys -- one burst minute carried 12694 journeys and 1033 of the failures | `.scratch/runtime-fixes/goal-rev426-ten-minute.json` |
| Eighteen minute window at revision 321 | 557863 requests, 64845 journeys, P99 257 milliseconds, 0 failed journeys and 0 unexpected HTTP errors | `.scratch/runtime-fixes/goal-rev321-eighteen-minute.json` |
| Eighteen minute window at revision 330 | 107442 requests in the first ten minutes, 12910 journeys, boarding record races on the fulfillment path | `.scratch/runtime-fixes/goal-rev330-ten-minute.json` |
| Ten minute resident cycle at revision 306 | 451150 requests, 49821 journeys, P99 2888 milliseconds, 7143 failed journeys | `.scratch/runtime-fixes/goal-rev306-ten-minute.json` |
| Ten minute resident cycle at revision 300 | 362212 requests, 41567 journeys, P99 226 milliseconds, 14 failed journeys | `.scratch/runtime-fixes/goal-rev300-ten-minute.json` |

Scratch logs and module build outputs are local evidence and are not versioned
repository artifacts.

## What is open

Everything still open is listed once, in [TODO](TODO.md). The largest items are
the general multistop capacity verification, the waitlist fulfillment
acceptance, durable event recovery, and the reporting monetary edge cases.

No part of this document authorises a deployment: the runtime configuration is
the single values file, and a release is made with
`skaffold run -p resident -d mirrors.tencent.com/microservice`.

## The resident round of 2026-09-26

Revisions 400 to 417 removed six defects between the load generator and its
verdict, each one measured before and after. P99 request latency over a ten
minute window fell from 1093 to 219 milliseconds, the request error groups from
twenty four to two, and failed journeys from 8859 of 56315 to 4208 of 66765.

| Defect | Measurement before | After |
|---|---|---|
| Every replica published the outbox, so 38 to 74 percent of four streams was a second copy | `.scratch/runtime-fixes/goal-rev401-ten-minute.json` | the same streams carry zero duplicates |
| seat-assignment decoded every seat map on every capacity facts call | 6.8 cores a replica, 4075m, 2.3Gi | 0.4 cores a replica, 240Mi, bounded cache |
| Capacity read the seat views inside the pool's admission lock | a Hold 85 to 112 seconds after the request at the ninety-fifth percentile | 0.2 to 0.8 seconds, 47 in the worst minute |
| Notification's queue-depth counter serialised every enqueue on one row | 144 sessions active, fare-pricing at 4.1 seconds median | 54 sessions, core from 8.5 to 2.9 cores |
| Capacity rewrote a sixteen kilobyte supply observation per admission | 95 writes a second, 6.6GiB of TOAST, autovacuum holding 26.6 percent of the admission time | zero writes while the map version holds |
| The OTLP gateway had one core and one gibibyte | OOM-killed seven times, refusing data, every traced service timing out at ten seconds | 0.8 cores of four, no refusals |

Two more followed: offer-management consumed the itinerary stream at 78 to 115
entries a second against 150 produced, so an offer was built from state that
arrived after the client had retried for a minute -- twelve handlers and a fourth
replica left it at zero lag and the window's offer failures went from 1180 to
none. P99 fell from 219 to 129 milliseconds and failed journeys from 4208 to
1640.

What is left is one shape: a post-sales case whose cancelled booking never
released its capacity hold. The hold stayed Confirmed because the release reached
capacity's dead letter during the gateway outage, the case waits for that release
with no deadline, and every later journey that reuses the order fails. The case,
the booking and the hold are named in [TODO](TODO.md).

## Hardcoded values audit

Audited 2026-09-26. Every literal the resident fixes add is a domain status, an
API path, a SQL statement, a cache key or a named bound; the only dates in them
are in comments recording a measurement, and the only repository values are test
fixtures. A sweep of first-party production code then found three places where a
value stood in for a fact.

| Where | What was hardcoded | What it is now |
|---|---|---|
| `services/traveler-profile` | every profile born `1970-01-01`, every document issued by `CN` | `dateOfBirth` and `documentIssuingCountry` come from the create request; an absent date means the profile carries none, and a document without an issuing country is a validation error |
| `services/transfer-management` | the builtin MCT matched station display names (`北京南`, `上海虹桥`, ...) against a node reference | the caller declares `stationSize` (`LARGE_STATION` 25, `MEDIUM_STATION` 20, `SMALL_STATION` 15); the reference keeps its own job |
| `services/loyalty-membership` | a five-entry public-holiday calendar and four corridor names matched inside a route string | the event states `isHoliday` and `isBonusRoute`, like `travelDate`; the calendar and the corridor list belong to the upstream that owns them |

The first two replaced a value the service invented for a fact the caller holds.
The third removed two rules that could not fire: no producer sends `travelDate`,
`routeCode` or `isHoliday` on a capture event today, so the bonuses were never
awarded, and `services/loyalty-membership/src/bootstrap.ts` already documents
that. Tests, the load generator and the stress harness keep their own literals;
`deploy/stress`'s pinned departure date is in [TODO](TODO.md) because it is
already in the past.

The three fixes were deployed at revision 425 and checked on the HTTP surface
before the window: a create with `dateOfBirth` reads the value back, one without
it reads `null` rather than `1970-01-01`, a document without
`documentIssuingCountry` is a 400 naming the field, and the load generator's
traveler creations carry the date. None of the three changed what the load
generator sees, and the revision 425 window carries 0.19 percent failed journeys
against 0.20 percent at revision 421.

## The compensation dead letter

`events:post-sales` gained a dead letter after the revision 425 rollout that the
revision 424 fixes did not cover: a `PostSalesApplied` for a `COMPENSATION` case,
five attempts, `domain rule violation: payment purchase reference returned 404`.
The handler treated every applied case as a refund -- a case id was itself the
evidence -- so a compensation, which issues a wallet credit and keeps the ticket,
reached the cancellation path and read a payment purchase reference that does not
exist because nothing was refunded. Revision 426 branches on the case type: only
`REFUND` and `CANCELLATION` end coverage. Measured: no dead letter has arrived on
`events:post-sales` or `events:journey-order` since the 12:01:12Z deployment;
their newest entries are unchanged at 12:01:04Z and 11:26:22Z.

## The burst is what still fails

The revision 426 window is not clean, and its shape is one minute: 12:09:35Z to
12:10:35Z carried 12694 journeys, against 7579 in the window's next busiest
minute, and 1033 of the window's 1407 failed journeys. In that minute risk-status,
case-status and refund-status fail, post-sales answers in 9.3 seconds at the
ninety-ninth percentile with the client's 10 second timeout expiring 73 times,
and fare-pricing's median is 1.7 seconds. That is the consumer-lag shape the
offer and post-sales fixes removed at the mean, returning under a three to eight
times burst. It is recorded in [TODO](TODO.md); no service in the chart has an HPA.

## Every service is on its shard

The chart had split PostgreSQL by workload shape and left thirteen services
naming a database on a target shard while still connecting to `postgres`, the
fallback alias for core. Revision 427 gives each of them its shard --
payment, entitlement-ticketing, post-sales, payment-channel and
finance-settlement to txn; fare-pricing, offer-management,
capacity-availability, service-plan and place-network to read; invoicing,
notification and risk-compliance to event -- and trims core's database list to
the sixteen services that have no other home. The target databases existed and
were empty, so each service's own migrations created its schema on first start.

Measured before and after, on the same load:

| Check | Before (core) | After (six instances) |
|---|---|---|
| Sessions on the fallback instance | 687 to 750 | 101 on core; 57 to 166 on the others |
| Sessions waiting on a lock | 84 to 98 | 0 |
| Autovacuum holding the instance | 7 minutes on notification's 8.3 GB task table | no long autovacuum observed |
| Risk assessment, order created to cleared | p95 3 to 15 seconds, max 19.8, at every burst | p95 0.07 to 0.27 seconds, max 1.2 |
| Consumer groups with lag above 50 | up to 20 000 entries on six groups | 0 |
| Dead letters | 30 001 on `events:journey-order`, 30 000 on `events:post-sales` | 0 on all five streams |

The split moved data as well as connections: a service on its new shard starts
from an empty database while the services that did not move kept rows that
referenced what it used to hold. Measured in the first window after the move,
4859 offers failed on "Fare Pricing quote has not been consumed" and 3267
post-sales reads answered `CaseNotFoundException`, because the quotes and cases
had been left behind on core. The resident state was therefore reset -- every
database truncated, Redis flushed, and the load generator's own checkpoint
removed, because it had persisted the old accounts, travelers and routes --
and the load generator bootstrapped again.

The first ten minute window after that reset is the first clean one recorded:
424214 requests, 47671 journeys, P99 117 milliseconds, **0 unexpected HTTP
errors and 0 failed journeys**
(`.scratch/runtime-fixes/goal-rev427b-ten-minute.json`). The window after it
carried the cycle's sharp burst -- 18828 journeys in one minute against 5000 to
7500 in the others -- and failed 4194 of 70691: fare-pricing quotes hit the
client's 10 second timeout 1458 times, journey-order answered 503 "Unable to
resolve authoritative order snapshot" 606 times, and post-sales timed out 523
times. That is a capacity limit on the request-serving replicas under the burst,
not the instance-wide contention the split removed; it is recorded in
[TODO](TODO.md).

That capacity limit turned out to be the pools rather than the replicas: at the
burst, post-sales had 281 requests waiting for a Hikari connection while each pod
used one core, fare-pricing had 25 waiting while the process held six of its
eight cores and answered quotes in 6.4 seconds at the median, and
identity-verification, whose eligibility lookup fare-pricing calls, had 12
waiting. Revision 430 raises post-sales' Hikari pool to 64, fare-pricing's
per-worker pool to 24 and identity-verification's to 12, which the three
instances hold (txn names 178 of 400 connections, read about 604 of 700, user
about 154 of 300). The burst window after it carries no timeout and no 503:
fare-pricing's ninety-ninth percentile is 107 milliseconds where it had been the
client's 10 second deadline, post-sales is 119 milliseconds where it had been the
same deadline, and the window's 24 failed journeys are all the disruption
recovery poll tail.

The next window showed that the pools were only half of it. At that burst
fare-pricing held 11.04 cores against the eight its pod may use, with 17 quotes
waiting for a connection, and its callers failed on it: journey-order answered
503 while building an order and post-sales answered 500 "I/O error on GET
http://fare-pricing:8080/api/v1/fare-quotes/...: Read timed out". Revision 431
gives fare-pricing a second replica (the same burst on sixteen cores) and moves
read's ceiling to 900 for its ninety six extra connections. The window after it
carries 607568 requests and 64894 journeys at P99 128 milliseconds with no
timeout at all: fare-pricing's ninety-ninth percentile is 156 milliseconds,
post-sales 142 and journey-order 157. What fails there -- 989 journeys -- is
every one a post-sales or recovery poll tail, the shape recorded in
[TODO](TODO.md).

## Consumer throughput is what the tails were

The refund and recovery tails both turned out to be consumer throughput rather
than slow handlers or slow dependencies, and both were fixed by the same shape:
a service whose work is per-event was running fewer consumers than the burst
delivers events to.

Payment ran twelve consumer threads across four streams. Its refund handlers were
never slow -- every run was under 423 milliseconds in the window that was
measured, and the database and the channel were idle -- yet a refund's approval
reached the handler five seconds after post-sales published it, and the refund
then settled eight seconds after that, which the owning case's client misses by
about a second. Revision 434 raises the count to thirty two; measured over the
twelve minutes after, **2838 of 2838 refunds settle under a second** (mean 0.27),
where before 875 of 17371 took more than eight seconds and 564 more than twelve.

disruption-recovery used the kit's `start_in_background`, which runs **one**
thread for every stream it is given, so four streams were handled one message at
a time. Its group fell 349 events behind on `events:post-sales` and 539 on
`events:journey-order` inside one burst minute, and a recovery case that
completes on `PostSalesApplied` took 24 seconds to reach RECOVERED against the
client's ten second poll. The kit gains `start_independent_streams` (one thread
per stream per worker, four by default) and revision 435 uses it. The window
after carries no disruption-status failure at all.

Offer Management had the same shape one layer down. The kit takes the batch path
when a handler defines one, and this one does -- it writes a whole batch of
itineraries in one statement, which was itself an earlier fix for pool
contention -- so `CONSUMER_BATCH_CONCURRENCY` never applied to it and the batch,
not the per-entry concurrency, was the unit of throughput. Measured at the burst:
its group's lag on `events:trip-planning` went from 2217 to 16981 entries in
thirty seconds and drained at about 190 a second, one itinerary was stored
thirty-five seconds after Trip Planning wrote it, and the client's twelve offer
attempts expired 223 milliseconds before it arrived -- the whole of the transfer
offer group in that window. Revision 440 gives it six instances, a read batch of
500 entries rather than the kit's default 100, and a warning when a batch takes
more than a hundred milliseconds. Its lag on that stream now peaks at **3**
across a cycle, and the window in [verification evidence](#verification-evidence)
carries no failure at all.

## Notification's delivery queue

Two defects in notification's durable delivery path surfaced once it ran on its
own shard, both fixed and deployed at revision 429.

The claim worker requested a batch of rows, and the queue trigger updates one
`(state, channel, bucket)` counter row per queue row inside that transaction;
two batches that shared two buckets in a different order deadlocked. The worker
now claims one row per transaction and keeps its concurrency by claiming again
as soon as a slot frees.

That was necessary but not sufficient: a claim (`Ready` to `Claimed`) and the
lease recovery (`Claimed` to `Ready`) touch the same two counter rows, and the
trigger took them in the order of the transition, so the two paths deadlocked
each other. Migration 013 takes them in the order of `(state, channel)`, which
removes the cycle and leaves the counts unchanged. Measured on event's log: 66
deadlocks and 380 `WORKER_CYCLE_FAILED` in two minutes before; zero notification
errors in the four minutes after the migration was applied.
