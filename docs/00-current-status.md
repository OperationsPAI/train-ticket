# Current project status

Last verified: 2026-09-25, Asia/Beijing.

## Runtime

The resident Helm release is `train-ticket` in namespace `train-ticket`,
revision **374**.
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
| Resident cycle on revision 377 | 450756 requests and 50345 journeys in 600 seconds, P99 1218 milliseconds, **12211 failed journeys** | `.scratch/runtime-fixes/goal-rev377-ten-minute.json` |
| Resident cycle on revision 386 | 455602 requests and 96165 journeys in 600 seconds, **19569 failed journeys**, dominated by Trip Planning search | `.scratch/runtime-fixes/goal-rev386-ten-minute.json` |
| Peak window inside revision 386's run | 0 failed journeys of 10670 in one window and 36 of 8924 in the next, both after the reservation fixes | resident load generator logs |
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

### The resident cycle is not yet clean

The cycle does not pass the acceptance in [TODO](TODO.md#acceptance) yet. The
last measured window carries 19569 failed journeys out of 96165, and the
reservation path is no longer what produces them:

| Group | Count in the window | Cause |
|---|---|---|
| `trip-planning` `search` timeout | 14153 | The search query reads the plan corpus, which the load generator's own bootstrap keeps growing; see the note below. |
| `trip-planning` `search` 503 | 2890 | The same query holding a pooled connection long enough to exhaust it. |
| `post-sales` `change-execute` 422 | several hundred | The replacement reservation is refused with `OVERLAPPING_HOLD` while the original hold is still active. |
| `disruption-recovery` `disruption-select` 503 | tens | A business conflict ("a refund or change case is already in progress for this order") mapped to an availability failure. |

The peak windows inside the same run, taken before Trip Planning degraded,
carried **zero failed journeys out of 10670** and 36 out of 8924, so the
reservation path itself now holds at the peak.

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

### Previously recorded windows

These windows cover earlier revisions and are kept for context; the current
revision's evidence is the cycle recorded above.

| Check | Result | Evidence |
|---|---|---|
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
