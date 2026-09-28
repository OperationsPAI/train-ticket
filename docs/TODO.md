# TODO

Updated: 2026-09-28, Asia/Beijing.

The single active backlog for the resident release, and it lists what is still
open and nothing else. Finished work is recorded with its measurements in
[current status](00-current-status.md), the telemetry contract is in
[business metrics](07-observability/business-metrics.md), and a closed item is
deleted rather than archived: git history keeps its text, current status keeps
its evidence.

Everything below is **deferred by the operator's instruction**. The traffic level
is fixed in `deploy/helm/train-ticket/values.yaml` at `target_rps: 20` with a
diurnal peak of `2.0` and a burst of `1.5` to `2.0` (product `4.0`), and further
throughput, scale and read-cost work is out of scope for this pass. None of it is
a correctness defect: at that level every acceptance window carries zero failed
journeys and zero unexpected HTTP errors, and the peak minute sits about 2.6x
below the 13572 journeys a minute where the first capacity failure was measured.

## T01: Give the Redis bus more headroom

The four bus items, in the order worth trying, kept because each is still the
reason the area would fail if the level were raised again.

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

## T19: Trip Planning's peak search, the shard cutovers, the write amplification, and the pool-lock admission

1. [ ] Trip Planning's search must hold at the peak. Its query reads the plan
   corpus, and the load generator's own bootstrap adds to that corpus on every
   run -- `plan_nodes` reached 907825 rows and `plan_segments` 748331 when this
   was measured. A covering index took one search from 1366 to 130-210
   milliseconds, which held for a few windows and then degraded again as the
   corpus grew. Measured with the index in place: 56 searches a second, each
   fetching about 20000 segment rows, which is 1.1 million tuples a second from
   the plan tables alone.

2. [ ] Cut the remaining databases over to their shards, if the split is wanted
   later. The chart defines six shards, but only six of the thirty-seven
   services set `pgInstance`; the other
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

4. [ ] Capacity's admission serialises on one advisory lock per inventory pool,
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
   constant memory, but it still reads seven hundred thousand pool documents
   (about five seconds). Pushing the fold into SQL would cut the transfer to the
   label combinations alone.

## Rules

- No unchecked item above is implicitly authorised: completing one means
  implementing it, deploying it, and attaching the code, deployment and business
  verification evidence before the item is deleted -- the evidence belongs in
  [current status](00-current-status.md).
- Runtime configuration is maintained in exactly one file,
  `deploy/helm/train-ticket/values.yaml`. The former cluster, case, resident,
  production and ACR overlays are deleted.
