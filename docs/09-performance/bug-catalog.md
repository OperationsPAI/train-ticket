# Bug Catalog — Stress Test Session 2026-07-11/12

Bugs discovered and fixed during stress testing. Each can be independently
reverted to reproduce the issue for RCA agent testing.

## BUG-001: Dead Consumer PEL Accumulation

**Severity**: Critical (blocks event processing)
**Services**: All 5 platform kits (Java, Python, Go, TypeScript, Rust)

**Symptom**: After scaling replicas down (e.g., 3→1), the surviving consumer
gets overwhelmed with 17K+ pending messages from dead consumers. Event
processing stalls, orders stay in CREATED state.

**Root Cause**: When a pod dies, its Redis consumer remains registered in the
consumer group. XAUTOCLAIM reclaims pending messages to surviving consumers,
but with thousands of stale messages, the surviving consumer's handler threads
are all busy retrying old events instead of processing new ones.

**Fix**: On subscriber startup, prune consumers idle >5 minutes via
`XINFO CONSUMERS` + `XGROUP DELCONSUMER`.

**Commits**: `048cb90c` (Java), `0e24e5a2` (Python), `a4fa1881` (Go),
`79adcb31` (TypeScript), `089f6a75` (Rust)

**Reproduce**: Revert the commit for one kit, scale a service from 3→1 under
load, wait 5 minutes for PEL accumulation.

---

## BUG-002: Concurrent Itinerary Overwrite Race

**Severity**: High (breaks offer creation under concurrency)
**Service**: offer-management (TypeScript)

**Symptom**: Under concurrent load, offer creation returns 422
"No consumed Fare Pricing quote matches" even though fare quotes exist.

**Root Cause**: `itineraryRef` is deterministic per route (hash of
origin+destination). When multiple loadgen pods search the same route
concurrently, they get different segmentRefs (different departure times).
The `ItineraryProposed` events overwrite each other in
`offer_upstream_itineraries` since itineraryRef is the same. When
offer-management looks up the fare quote using the stored itinerary's
segmentRefs, it gets the wrong segments and the hash doesn't match.

**Fix**: Add optional `segmentRefs` to the offer creation request. When
provided, offer-management uses these directly for the fare quote hash
lookup instead of the potentially-overwritten stored itinerary segments.

**Commit**: `a9ca675e`

**Reproduce**: Revert the commit, run 30+ loadgen pods against single
offer-management instance. Offer 422 rate will be ~100%.

---

## BUG-003: Redis XREVRANGE Scan Timeout

**Severity**: High (blocks booking saga completion)
**Service**: loadgen staff workers

**Symptom**: Staff reservation workers timeout with "Timeout reading from
redis:6379". Purchase success drops from 86% to 33%.

**Root Cause**: Staff workers used XREVRANGE to scan the
booking-orchestration Redis stream for BookingSagaStarted events. With
100K+ entries in the stream and 120 concurrent workers (30 pods × 4
workers), the scans overwhelmed Redis.

**Fix**: Replace XREVRANGE scan with HTTP API lookup
`GET /booking-sagas/by-order/{orderId}`. The booking-orchestration
endpoint is backed by a simple PostgreSQL query with index — O(1) vs O(N).

**Commit**: `bb8fb91b`

**Reproduce**: Revert to XREVRANGE-based saga discovery, run 30+ loadgen
pods. Redis CPU will spike and staff workers will timeout.

---

## BUG-004: capacity-availability Outbox Relay Stall

**Severity**: Critical (blocks entire booking saga)
**Service**: capacity-availability (Rust)

**Symptom**: Booking sagas stuck in RESERVING state. 96K sagas never
complete. capacity-availability event stream has 0 messages.

**Root Cause**: During Redis OOM (maxmemory 512MB), the outbox relay
failed with "OOM command not allowed" and entered a permanent error loop.
Even after Redis was fixed (maxmemory increased to 6GB), the Rust
service's outbox relay never recovered — it kept logging the same error.

**Fix**: Restart capacity-availability pod.

**Lesson**: Outbox relay should implement exponential backoff with
periodic retry instead of tight error loop.

---

## BUG-005: offer-management In-Memory State Split

**Severity**: Critical (92% offer failure rate)
**Service**: offer-management (TypeScript)

**Symptom**: offer-management returns 422 for 92% of offer requests.

**Root Cause**: With 2 replicas in the same Redis consumer group, events
from `events:fare-pricing` are distributed across pods. Each pod's
`InMemoryUpstreamStateRepository` only has half the fare quotes. HTTP
requests hit either pod via k8s service load balancing, so most lookups
miss.

**Fix**: Scale to 1 replica (immediate), or persist upstream state to
PostgreSQL (proper fix — `offer_upstream_fare_quotes` table exists).

---

## BUG-006: PG WAL Disk Exhaustion

**Severity**: Critical (crashes all PG instances)
**Service**: All PostgreSQL shards

**Symptom**: `FATAL: could not write to file "pg_wal/xlogtemp": No space
left on device`. All 6 PG instances crash, entire system down.

**Root Cause**: Setting `wal_level=logical` with unused replication slots
(created by CDC relay setup). Logical WAL segments cannot be recycled
until consumed by the slot's consumer. Since the slots were never
connected to a consumer, WAL accumulated indefinitely until 40GB PVC full.

**Fix**:
1. Set `max_slot_wal_keep_size=2GB` to cap WAL retention
2. Drop unused replication slots: `SELECT pg_drop_replication_slot(slot_name) FROM pg_replication_slots`
3. Use LISTEN/NOTIFY instead of WAL-based CDC (no slots needed)

**Additional trap**: Removing `wal_level=logical` from startup args while
replication slots exist causes a different fatal: `logical replication
slot exists, but wal_level < logical`. Must drop slots BEFORE changing
wal_level.

---

## BUG-007: journey-order Consumer PEL Overflow

**Severity**: High (orders stay in CREATED)
**Service**: journey-order (Java)

**Symptom**: Orders never transition from CREATED to CONFIRMED. 17K
pending events on payment and entitlement-ticketing streams.

**Root Cause**: After scaling from 3→1 replicas, dead consumers' pending
messages were auto-claimed to the surviving consumer. The consumer's 4
handler threads were all busy processing stale account/traveler-profile
events (DLQ-bound retries), blocking critical payment/entitlement events.

**Fix**: Same as BUG-001 (dead consumer pruning). Also: reset consumer
group with `XGROUP DESTROY` + `XGROUP CREATE $` to clear backlog.

---

## BUG-008: Invoicing Missing Booking-Orchestration Subscription

**Severity**: Critical (blocks saga completion at INVOICING step)
**Service**: invoicing (Rust)

**Symptom**: Booking sagas accumulate in INVOICING status and never reach
COMPLETED. Regular customer purchases never fully complete.

**Root Cause**: Invoicing service did not subscribe to
`events:booking-orchestration` stream. When booking-orchestration
published `InvoiceRequested` events, invoicing never received them.
Additionally, the `paymentRef` field was required but
booking-orchestration's `paymentRefForSaga()` returns null when no
payment_intent_saga_ref exists for the saga.

**Fix**:
1. Add `events:booking-orchestration` to `subscribed_streams()`
2. Make `paymentRef` optional in `handle_saga_invoice_requested()`

**Commit**: `21d4360c`

**Reproduce**: Revert the commit, run the full purchase flow. Sagas will
stall at INVOICING status indefinitely.

---

## BUG-009: Redis Liveness Probe Too Aggressive

**Severity**: Medium (causes unnecessary Redis restarts)
**Service**: Redis

**Symptom**: Redis restarts 8+ times in 11 hours. All consumer groups
and stream positions are lost on restart (save disabled), causing
services to re-read 100K+ historical events.

**Root Cause**: Default liveness probe timeout (1s) and failure
threshold (3) are too tight. Under load, `redis-cli ping` takes >1s
occasionally, triggering false positives.

**Fix**: Increase `timeoutSeconds: 5` and `failureThreshold: 5`.

**Commit**: `8ce213f5`

---

## BUG-010: PG Fresh PVC Missing Remote Access

**Severity**: High (new PG instances reject pod connections)
**Service**: All PostgreSQL shards

**Symptom**: After PVC recreation (e.g., postgres-event), services get
`no pg_hba.conf entry for host` errors and CrashLoopBackOff.

**Root Cause**: Docker postgres entrypoint normally adds
`host all all all scram-sha-256` to pg_hba.conf, but edge cases
(PGDATA on PVC with partial init) can leave this entry missing.

**Fix**: Add pg_hba.conf check to initdb script to ensure
`host all all 0.0.0.0/0 md5` is present.

**Commit**: `8ce213f5`

---

## BUG-011: Loadgen Legacy Journey Missing Identity Verification

**Severity**: Medium (all legacy journeys fail)
**Service**: loadgen

**Symptom**: Legacy journey `preserve` step always fails with "Identity
verification is not passed for all travelers".

**Root Cause**: The legacy journey creates a traveler but never calls the
identity-verification service. The regular purchase journey calls
`p.Identity(ctx, tvl)` before creating orders, but the legacy journey
skipped this step.

**Fix**: Add identity verification call before `preserve` step, matching
the purchase journey pattern.

---

## BUG-012: Loadgen Staff XREVRANGE Saga Discovery (BUG-003 Regression)

**Severity**: Critical (blocks all purchase saga completion)
**Service**: loadgen staff workers

**Symptom**: Staff reservation workers take 80+ seconds to find saga IDs.
Purchase journeys time out (90s default) before staff completes
reservation. Purchases fail with "timed out waiting for sb".

**Root Cause**: Staff workers use `XREVRANGE` on `events:booking-orchestration`
stream to scan for `BookingSagaStarted` events matching an order ID. This
is O(N) per lookup with 100K+ stream entries. BUG-003 fix was supposed to
replace this with HTTP API, but the fix was never applied to the current
loadgen image.

**Fix**:
1. Add `GET /booking-sagas/by-order/{orderId}` endpoint to booking-orchestration
2. Replace XREVRANGE scan with HTTP API call in staff workers
3. Reduce polling from 20×4s to 15×2s

---

## BUG-013: Capacity Pool Size Too Small and Hardcoded Date

**Severity**: High (capacity exhausted quickly under load)
**Service**: capacity-availability (Rust)

**Symptom**: All inventory pools exhausted after ~100 bookings per route.
Scalpers report "capacity_exhausted". Provider-integration returns
NO_AVAILABLE_CAPACITY.

**Root Cause**: Default pool size was `req.quantity.max(100)` = 100 seats
per segment/class combination. Also, the pool service date was hardcoded
to "2026-07-05" regardless of actual departure date.

**Fix**: Increase default pool to 500 seats. Use current date from
`unix_millis_to_date(now_millis())` instead of hardcoded date.

---

## Performance Findings (Not Bugs)

### PERF-001: Python Single Uvicorn Worker
All 9 Python services ran with single worker (default). Under concurrent
load, requests serialize through GIL. Fix: `--workers 4` in Dockerfile.
Impact: trip-planning p50 1327ms → 193ms.

### PERF-002: PG 24 DBs on Single Instance
postgres-core hosted 24 databases. Under load, CPU reached 1176m with
heavy cross-DB contention. Fix: split to 6 shards.
Impact: postgres-core 1176m → 27m.

### PERF-003: Outbox Relay 600ms Latency
Per-service outbox polling at 50ms interval, but under DB contention
actual relay latency was 600ms. Fix: CDC relay with LISTEN/NOTIFY.
Impact: 600ms → <100ms per event hop.

### PERF-004: trip-planning Python CPU Ceiling
Python trip-planning hits 2c CPU limit at ~170 RPS. Fix: Rust rewrite
(trip-planning-rs). Impact: p50 107ms → 13.7ms, p95 2919ms → 184ms.

### PERF-005: Invoicing PG COMMIT Latency
postgres-event COMMIT takes 1-2s per event. Invoicing service falls
minutes behind the event stream, blocking saga completion at INVOICING
step. Orders get CANCELLED by saga compensation before reaching
CONFIRMED. Fix: batch commits or move invoicing to dedicated PG.

### PERF-006: Staff Queue Starvation
Original strict-priority queue (reservation > ticketing > dispatch)
caused ticketing starvation under load (51 tickets in 13min). Fix:
Go `select` fair scheduling across all queues. Impact: ticketing
went from 51 to 169 in 2min.

### PERF-007: Scalper XREVRANGE (BUG-003 Variant)
Scalper saga discovery used XREVRANGE on 36K+ entry stream. Same
O(N) scan as BUG-003 but in the scalper path. Fix: HTTP by-order
endpoint. Impact: scalper success from 0% to 57%.
