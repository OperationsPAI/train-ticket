# TODO

Updated: 2026-09-28, Asia/Beijing.

The single active backlog for the resident release, and it lists what is still
open and nothing else. Finished work is recorded with its measurements in
[current status](00-current-status.md), the telemetry contract is in
[business metrics](07-observability/business-metrics.md), and a closed item is
deleted rather than archived: git history keeps its text, current status keeps
its evidence. The design for the items that outgrow a single code change is in
[scale architecture](09-performance/scale-architecture.md).

Everything below is **deferred by the operator's instruction**. The traffic level
is fixed in `deploy/helm/train-ticket/values.yaml` at `target_rps: 20` with a
diurnal peak of `2.0` and a burst of `1.5` to `2.0` (product `4.0`), and further
throughput, scale and read-cost work is out of scope for this pass. None of it is
a correctness defect: at that level every acceptance window carries zero failed
journeys and zero unexpected HTTP errors, and the peak minute sits about 2.6x
below the 13572 journeys a minute where the first capacity failure was measured.

## T01: Give the Redis bus more headroom

The three bus items left, in the order worth trying, kept because each is still
the reason the area would fail if the level were raised again. The read is now
micro-batched: all five kits accumulate on a 50 millisecond tick and acknowledge
the round in one `XACK`, so a burst is one round instead of one round per entry.

1. [ ] Reduce the groups per stream further. Measured now: fourteen groups read
   `events:post-sales` and `events:journey-order`, ten read
   `events:booking-orchestration`, nine read `events:payment` and
   `events:ancillary-service`, and 123 read streams in total. The cheap cut is
   an audit for a group whose service acts on no type the stream carries, the
   one already made for Journey Order. The designs are in
   [scale architecture](09-performance/scale-architecture.md).

2. [ ] Or give the bus more than one thread: shard streams across Redis
   instances, or move the reads to a transport with parallel work. This is the
   only step that removes the ceiling rather than raising it, and it is a change
   to the deployment's shape rather than to a service.

3. [ ] Decide whether the acceptance envelope includes a burst the steady cycles
   never reach. If it does, the arithmetic in
   [current status](00-current-status.md#the-configured-envelope-is-wider-than-any-window-has-measured)
   says the system must be sized for 640 journeys a second, and the largest
   observed is 358.

## T19: Trip Planning's peak search, the write amplification, and the pool-lock admission

1. [ ] Trip Planning's search must hold at the peak. Its query reads the plan
   corpus, and the load generator's own bootstrap adds to that corpus on every
   run -- `plan_nodes` reached 907825 rows and `plan_segments` 748331 when this
   was measured. A covering index took one search from 1366 to 130-210
   milliseconds, which held for a few windows and then degraded again as the
   corpus grew. Measured with the index in place: 56 searches a second, each
   fetching about 20000 segment rows, which is 1.1 million tuples a second from
   the plan tables alone.

2. [ ] `seat_maps` retention. `capacity_supply_observations` now sweeps its own
   rows past a seven day window (`CAPACITY_SUPPLY_RETENTION_HOURS`), which is
   safe because nothing reads it. `seat_maps` cannot be swept by age: it holds
   204466 rows in 16 GB and keeps every version, and replacement lineage
   resolution still reads retired maps to place a hold whose unit left the
   current composition. Decide between sweeping only unreferenced maps and
   keeping a compact lineage projection, or resetting the resident state
   between runs -- every database truncated and Redis flushed, as the shard
   cutover last did. The designs are in
   [scale architecture](09-performance/scale-architecture.md).

3. [ ] Capacity's admission serialises on one advisory lock per inventory pool,
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
   that transaction. The service now logs that breakdown for any admission over
   200 milliseconds (`src/adapters/storage/mod.rs`), and the timing already
   shows the lock's critical section does not contain the supply fold. What is
   left is to aggregate the line under load and decide between dropping the
   pessimistic lock for the existing optimistic `expected_version` retry and
   shrinking the document each admission rewrites. See
   [scale architecture](09-performance/scale-architecture.md).

## Operational follow-ups

Two reads that were made safe rather than fast, each bounded by its cadence
rather than by its cost.

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
   constant memory, but it still reads every pool document (measured 306286,
   about five seconds). The SQL push-down was implemented and measured: it is
   exactly equivalent, but no pool is hold-free, so it saves no transfer and
   costs a second scan, and it was dropped. The lever is retiring terminal holds
   on every pool, not only on the one being written -- which also stops the
   published `released` level from being a lifetime count for a pool that is
   never written again. That changes a published level, so it is a decision; the
   measurement is in [scale architecture](09-performance/scale-architecture.md).

## Rules

- No unchecked item above is implicitly authorised: completing one means
  implementing it, deploying it, and attaching the code, deployment and business
  verification evidence before the item is deleted -- the evidence belongs in
  [current status](00-current-status.md).
- The fulfillment consumer loop count is sized for the segment stream's own
  burst (twelve), not for the entitlement stream alone (eight): the next
  measurement to make is whether the pool, not the loops, is the ceiling once
  that backlog drains inside one window.

- Runtime configuration is maintained in exactly one file,
  `deploy/helm/train-ticket/values.yaml`. The former cluster, case, resident,
  production and ACR overlays are deleted.
