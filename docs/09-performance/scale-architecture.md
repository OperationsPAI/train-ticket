# Scale architecture for the deferred throughput work

Updated: 2026-09-28, Asia/Beijing.

The [TODO](../TODO.md) keeps a set of throughput items deferred while the
resident release runs at the fixed traffic level (`target_rps: 20`, a diurnal
peak of `2.0`, a burst of `1.5` to `2.0`). This document is the design for the
ones that are not a code change on their own: what each would take, the options
and their tradeoffs, and how it would be verified. Nothing here is implemented.

The measurements below were taken on the resident release in namespace
`train-ticket`.

## The event bus is one thread

Every stream lives in one Redis instance (3 GB `maxmemory`, `noeviction`, no
persistence volume, one CPU-bound server process). Redis serves commands on a
single thread, so the bus's ceiling is the total command cost, and the
cumulative `INFO commandstats` names where it goes:

| Command | Calls | CPU | Per call |
|---|---|---|---|
| `xreadgroup` | 863,172,848 | 32,780 s | 37.98 µs |
| `xack` | 491,733,225 | 2,340 s | 4.76 µs |
| `xautoclaim` | 178,041,423 | 2,245 s | 12.61 µs |
| `xadd` | 137,072,675 | 1,071 s | 7.81 µs |
| `xpending` | 120,259,556 | 516 s | 4.29 µs |

`xreadgroup` is the ceiling by a wide margin, and its cost is per *call*, not
per entry: a reader that is keeping up returns one to three entries and pays a
full call for them. Two levers follow, in order.

### First lever: fewer calls per entry (T01.1, implemented)

Accumulate on a short tick before reading and acknowledge the round in one
`xack`. This divides both `xreadgroup` and `xack` calls by the batch size,
which is the cheapest change because it needs no new infrastructure. It is
implemented across the five platform kits; its effect is measurable in
`cmdstat_xreadgroup` and `cmdstat_xack` per minute.

### Second lever: fewer readers (T01.2)

A consumer group is a reader. The busiest streams carry many:

| Stream | Groups |
|---|---|
| `events:post-sales` | 14 |
| `events:journey-order` | 14 |
| `events:booking-orchestration` | 10 |
| `events:payment` | 9 |
| `events:ancillary-service` | 9 |
| `events:entitlement-ticketing` | 7 |

123 groups read streams in total. Each group is a cursor, a pending list and a
blocking read round of its own, and each round is an `xreadgroup` call on the
single thread. There are three ways to cut the count, in ascending order of
change:

1. **Audit for no-op subscriptions.** A group whose service acts on no event
   type the stream carries still pays a read round for every event. Journey
   Order already dropped `booking-orchestration`, `offer-management` and
   `traveler-profile` for exactly this reason
   (`RedisJourneyOrderSubscriptions`); the same audit over all 123 groups is
   mechanical and needs no protocol change.
2. **Fan-out through a router.** One reader per source stream consumes it and
   re-publishes a per-consumer stream, so the source has one group instead of
   fourteen. This trades groups on hot streams for extra `xadd`/`xreadgroup`
   calls on router streams, so it only wins when it collapses several cold
   readers into one and the consumers are individually slow.
3. **Read one stream per group.** A service that today reads five streams in
   one `XREADGROUP ... STREAMS a b c d e` still issues one call per round. The
   number of calls is set by the number of rounds, not the number of streams,
   so this does not help unless the call is split.

Recommendation: do (1) as an audit and keep (2) in reserve for the two streams
with fourteen groups. Neither is worth doing before T01.1, which removes most
of the calls for free.

### Third lever: more than one bus thread (T01.3)

This is the only step that removes the single-thread ceiling rather than
lowering the calls against it. Options:

- **Shard streams across N Redis instances, client side.** The shard key is the
  stream name. A producer owns its own streams, so publishing needs no change;
  a consumer opens one connection per bus and reads the streams on each. The
  kits gain a `stream -> bus` map that defaults to a single bus, so the
  deployment can start with one instance and add more without a code change.
  This is the smallest change that keeps the transport semantics (consumer
  groups, pending lists, `xautoclaim`) identical.
- **Redis Cluster.** The server shards by key hash slot automatically, but a
  blocking `XREADGROUP` may only name streams in one slot, so a reader that
  reads several streams must issue one call per stream and the client must know
  the slot. More client change than (a), and no benefit over it here.
- **A proxy in front of several instances.** Transparent for `xadd`, but
  blocking reads and per-group pending state are exactly what proxies handle
  badly.
- **Replace the transport** (Kafka, NATS JetStream). The outbox already gives
  at-least-once with per-consumer checkpoints and dead letters, which is the
  part that is hard to keep; this is a project, not a step.

Recommendation: (a). It preserves the contract the services already implement,
needs only a connection-per-bus in the kits and a `stream -> bus` map in the
chart, and its effect is directly measurable by watching `xreadgroup` CPU per
instance fall as streams move.

## The acceptance envelope (T01.4)

The arithmetic in [current status](../00-current-status.md) says the configured
envelope needs sizing for 640 journeys a second while the largest observed is
358. That is a decision, not a defect: acceptance either includes a burst the
steady cycles never reach or it does not.

The chart already fixes the envelope (`target_rps`, the diurnal multiplier, the
burst multiplier and its product). The recommendation is to state the
acceptance criterion explicitly against it -- "zero failed journeys and zero
unexpected HTTP errors within the configured envelope" -- and treat the burst
as a separate, labeled scale test with its own target. Then T01 and T19 are
scale work with a defined pass line rather than an open question about whether
the resident cycle is clean. `templates/hpa.yaml` exists; if the burst is in
scope, its scaling signal must be a request-rate or latency metric rather than
CPU, which lags the burst by the time a pod takes to start.

## Trip Planning's peak search (T19.1)

The search reads the plan corpus through a covering index. Measured on the
resident release: `plan_nodes` 322,454, `plan_segments` 281,017, 343 MB, index
`idx_plan_segments_search_covering` on
`(departure_date, origin_stop_ref, departure_time) INCLUDE (segment_ref,
scheduled_service_ref, destination_stop_ref)`. The index helped and then
degraded because the corpus grows: the load generator's own bootstrap registers
new services and dates on every run.

Options:

- **Bound the corpus.** Give the plan tables the same retention discipline the
  platform tables have, or rotate the corpus on the resident reset cadence.
  This is the same lever as the resident reset and is the reason the current
  numbers are a third of the ones the TODO recorded.
- **Narrow the fetch.** Each search fetches about 20,000 segment rows because
  the interval scan spans a wide window. A two-step query -- candidate services
  by node index first, then segments for those services only -- cuts the rows
  fetched per search.
- **Precompute connections.** A materialized table keyed by
  `(origin, destination, date)` refreshed offline turns a search into a small
  read, at the cost of a refresh pipeline and its staleness.
- **More read capacity.** The read shard already hosts `trip_planning`; a
  replica or more parallel workers raises the ceiling without changing the
  query.

Recommendation: bound the corpus and narrow the fetch first; precompute only if
the peak still misses after both.

## `seat_maps` retention (T19.2)

`seat_maps` cannot be swept by age the way `capacity_supply_observations` can.
Replacement lineage resolution reads retired maps: when a seat map is
superseded, an allocation pinned to the retired unit is resolved by walking
`ReplacesSeatMapID` back to the map that held it
(`resolveBoundPhysicalUnit`). Deleting a retired map breaks that resolution.

Options:

- **Sweep only unreferenced maps.** Delete a superseded map when no live hold
  or allocation names its `seatMapId`/version. The hold's supply evidence
  carries both, so the reference set is queryable.
- **Keep a compact lineage index.** Resolution only needs
  `SeatUnitRef -> locator` per class for a retired map, not the whole document.
  Storing that projection and dropping the full retired document keeps
  resolution working at a fraction of the bytes.
- **Bound the lineage depth.** Keep only the maps in a chain reachable from a
  current map, delete the rest.

Recommendation: sweep only unreferenced maps, and measure how many retired maps
are still referenced before choosing between the first and second option. This
is why the write-amplification item now offers retention *or* the resident
reset: the reset sidesteps the reference question entirely.

## Payment conservation (Operational follow-up 1)

The captured-amount aggregate over `payment_intent_snapshots` scans 1.17M rows.
After `VACUUM (ANALYZE)` it plans an `Index Scan using
payment_intent_captured_amount_idx` at 16 seconds; the remaining cost is
per-row expression evaluation, not heap access. So the fix is not an index.

Options:

- **Incremental counter.** Maintain the captured total in a small table updated
  in the same transaction as the snapshot write, with a periodic full
  reconciliation against the aggregate so drift is detectable.
- **Pre-aggregate by bucket.** A rollup keyed by `(currency, day)` updated with
  the snapshot turns the refresh into a read of one row per bucket.
- **Lower the cadence.** Keep the full aggregate but run it less often. This is
  what "made safe rather than fast" already does.

Recommendation: the incremental counter with reconciliation. It turns a
16-second scan into a one-row read and makes the reconciliation the only place
the full aggregate runs.

## Capacity's admission lock (T19 item 3)

The phase timing added to the synchronous admission shows the per-pool advisory
lock's critical section does *not* contain the expensive supply fold:
`admission_supply` runs before `lock_inventory`. What the lock covers is the
pool document read and rewrite plus the outbox, snapshot and idempotency
appends. So the constraint is not "too much reading under the lock" but "every
admission for one `pool:{segmentRef}:{classRef}` rewrites the same document",
and admissions into one pool queue behind each other.

Options, cheapest first:

1. **Drop the pessimistic lock and keep the optimistic check.** The path
   already carries `expected_version` with `MAX_RETRIES = 3`; the advisory lock
   converts a conflict into a queue. Removing it turns queueing back into
   retries, and a retry counter proves whether that is safe.
2. **Shrink the written row.** Each admission rewrites the whole pool document,
   holds inline -- the same shape that made the observation table's TOAST a
   problem. Holding the holds as rows and keeping the pool row to counters and
   a version cuts both the lock hold and the write amplification.
3. **Finer lock granularity.** Lock by `(pool, capacityUnitRef)` so different
   units admit in parallel. This needs the unit choice to be decidable without
   the whole pool state.
4. **More replicas does not help.** The lock is in the database, so extra pods
   only add waiters.

Recommendation: (1) first, then (2) if the retry rate says the conflict is real.
The verification is the new slow-admission line aggregated per phase: whether
`poolLock` or `poolSave` dominates the p95 answers (1) versus (2).

## The capacity levels fold (Operational follow-up 2)

The refresh rebuilds the levels by streaming every `inventory_pool_snapshots`
document (measured 306286) through the fold every
`CAPACITY_METRICS_REFRESH_MS`. The TODO's premise was that pushing the fold into
SQL would transfer only the label combinations instead.

That was implemented and measured. A two-stage split -- SQL sums the pools that
carry no hold, the fold streams the rest, so no domain rule (travel-class
mapping, standing substitution, overbooking widening, aging) moves into SQL --
is exactly equivalent to the single pass, and the equivalence is covered by
fixtures including an empty-holds pool, a pool with only terminal holds and a
standing pool. But on the live data **no pool is hold-free**: 0 of 306286. The
generator bootstraps a segment, the segment takes a hold, and
`retire_terminal_holds` only runs when a pool is written, so a pool that is
never written again keeps its terminal holds forever. The two-stage read is
therefore a net negative today -- a second full-table scan for no reduction in
transfer -- and was not shipped.

The real lever is the retirement rule: retire terminal holds on every pool, not
only on the one being written. That also fixes a semantic drift the measurement
exposed. `CapacityUnitState::Released` is documented as a recent-release window,
but for a pool that is never written again it is a lifetime cumulative count.
Retiring terminal holds on a timer changes the published `released` level, so
it is a decision rather than a pure optimisation: pick the window, then the
fold's cost falls with the number of pools that become hold-free.

## Service settings

The Go services read their runtime from the pod's environment, and a release
declares what it sets in the same chart that deploys it
(`services.<name>.env` in `deploy/helm/train-ticket/values.yaml`). An unset
setting keeps the value the image ships, so a release that declares nothing new
deploys the behaviour the tree already had. `services/service-plan` sizes the
page its segment listing serves through `SERVICE_PLAN_LISTING_PAGE_LIMIT`.

## Rollout

Of the deferred items, three were a code change on their own and are now
implemented: T01.1 (the accumulate-and-batch read in all five kits), the
`capacity_supply_observations` retention sweep, and the phase timing that
answers where a slow admission's hold time goes. T01.2's audit is mechanical and
still open.

The rest each need a decision the operator owns, and none can be validated at
the fixed traffic level because the failure they address does not reproduce
there: T01.3 and T19.1 change the deployment's shape, the `seat_maps` and
payment designs change a data-lifetime rule, and the capacity lock and the
levels fold both turn on a semantics question -- whether the pool lock becomes
optimistic retries, and whether terminal holds are retired on every pool, which
changes the published `released` level.
