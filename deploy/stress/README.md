# Stress Test Infrastructure

Open-loop stress driver and post-run correctness auditor for the train-ticket
microservice system running on `kind-arl-test`.

## Prerequisites

```bash
pip install aiohttp pyyaml
```

The auditor also requires `kubectl` configured with context `kind-arl-test`
and access to the `train-ticket` namespace.

## Quick start

### 1. Run a stress scenario

From the e2e-curl pod (in-cluster) or from the host with port-forward:

```bash
# Rush scenario: 200 workers competing for 50 seats, closed-loop
python3 driver.py --scenario scenarios/s1-rush.yaml --report /tmp/s1-report.json

# Refund storm: seed purchases first, then refund those exact order refs
python3 driver.py --scenario scenarios/s1-rush.yaml \
    --workers 5 --duration 60 --report /tmp/s3-seed-report.json
python3 driver.py --scenario scenarios/s3-refund-storm.yaml --report /tmp/s3-report.json

# Staircase scenario: open-loop with stepped RPS from 10 to 150
python3 driver.py --scenario scenarios/s2-staircase.yaml --report /tmp/s2-report.json
```

CLI overrides:

```bash
# Override RPS, duration, and worker count
python3 driver.py --scenario scenarios/s2-staircase.yaml \
    --rps 50 --duration 120 --workers 100 \
    --report /tmp/custom-report.json
```

### 2. Audit the results

After the driver finishes, run the auditor to check six correctness invariants:

```bash
python3 auditor.py \
    --scenario scenarios/s1-rush.yaml \
    --report /tmp/s1-report.json \
    --output /tmp/s1-audit.json
```

The auditor queries PostgreSQL and Redis directly via `kubectl exec` and
verifies:

1. **Inventory conservation** -- confirmed orders <= segment capacity
2. **Seat uniqueness** -- no duplicate seat assignments per segment
3. **Fund conservation** -- captures match order totals; refunds <= captures
4. **No stuck orders** -- all sagas terminal, outbox drained, DLQ empty
5. **Idempotent single-effect** -- each idempotency key produces exactly one effect
6. **Clean losers** -- rush failures are contractual, not 500s

Exit code 0 = all assertions passed; exit code 1 = at least one failed.

### 3. Running from the e2e-curl pod

```bash
kubectl --context kind-arl-test exec -it deploy/e2e-curl -n train-ticket -- bash
cd /stress
python3 driver.py --scenario scenarios/s1-rush.yaml
```

`driver.py` imports `request_records.py` from its own directory, so copy both
when staging into a pod. It resolves via the script's directory (not the cwd),
so `python3 /stress/driver.py` works from anywhere; `python3 -m driver` from
another directory does not.

Records land next to the report, i.e. under `/tmp` inside the pod by default.
That is the container's writable layer, so copy them out before the pod goes
away:

```bash
kubectl -n train-ticket cp deploy/e2e-curl:/tmp/s1-report-requests.jsonl \
    ./s1-requests.jsonl
```

## Scenario format

See `scenarios/s1-rush.yaml` and `scenarios/s2-staircase.yaml` for annotated
examples. Key fields:

| Field | Description |
|-------|-------------|
| `load.model` | `open` (token bucket) or `closed` (fixed workers) |
| `load.rps` | Target requests/second (open-loop only) |
| `load.workers` | Concurrent coroutines |
| `load.staircase` | List of `{rps, hold_seconds}` steps |
| `mix.purchase` | Weight for purchase chains |
| `mix.refund` | Weight for refund chains |
| `mix.browse` | Weight for browse chains |
| `constraints.target_capacity` | Expected seat count (for auditor assertions) |

## Architecture

```
driver.py
  TokenBucket       -- open-loop arrival control
  LatencyTracker    -- per-endpoint p50/p95/p99/max
  StressDriver      -- reads scenario, spawns workers
  purchase_chain()  -- search -> quote -> offer -> order -> reserve -> pay -> ticket
  refund_chain()    -- post-sales case -> evaluate -> approve
  browse_chain()    -- search + quote, no purchase
  StaffSim          -- drives reservation + ticketing queues

request_records.py
  RequestRecorder   -- one JSON Lines row per request, written off the event loop
  new_trace_context -- W3C traceparent minted per request
  route_template()  -- collapses ids so rows group by endpoint

auditor.py
  query_db()        -- kubectl exec psql
  query_redis()     -- kubectl exec redis-cli
  6 assertion fns   -- each returns AssertionResult
```

## Output

The driver writes a JSON report with:
- Per-chain latency histograms
- Per-endpoint latency histograms
- HTTP status code counts
- Outcome tallies (purchased, refunded, failed, etc.)
- Successful purchase refs (`purchases`) that can seed follow-up refund-only runs

The auditor writes a JSON audit report with pass/fail for each assertion.

### Per-request records

Alongside the aggregate report (which is unchanged), each run writes **one
JSON Lines row per client HTTP request** — by default to
`<report-path-without-.json>-requests.jsonl`:

```bash
python3 driver.py --scenario scenarios/s1-rush.yaml --report /tmp/s1-report.json
#  -> /tmp/s1-report.json           aggregate report, as before
#  -> /tmp/s1-report-requests.jsonl one row per request

python3 driver.py --scenario scenarios/s1-rush.yaml --records /tmp/rows.jsonl
python3 driver.py --scenario scenarios/s1-rush.yaml --no-records   # disable
```

The report's percentiles and status counts can only answer questions asked
before the run started. One row per request lets you recompute a quantile over
any sub-window, split by endpoint or chain, compare two runs, and jump from a
slow or failed request straight to its server-side spans in Jaeger.

```json
{"ts":"2026-09-07T07:41:26.472196Z","chain":"purchase","step":"confirm-order",
 "service":"order","method":"POST","route":"/api/v1/orders/{id}/confirm",
 "path":"/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm","status":201,
 "latency_ms":12.346,"error":"","trace_id":"b0cf9eff63516f81efcd1daaf347da1e",
 "span_id":"d307da84f5b9f9a7","sampled":true}
```

| Field | Meaning |
|-------|---------|
| `ts` | Request **start**, RFC3339 with microseconds, UTC |
| `chain` | `purchase` / `refund` / `browse` / `staff` / `bootstrap` |
| `step` | Call-site step label |
| `service` | Target service |
| `method` | HTTP method |
| `route` | Route template — id-looking segments collapsed to `{id}`, query dropped |
| `path` | Raw requested path |
| `status` | HTTP status; **`0` means no response was ever received** |
| `latency_ms` | Wall time around the request, 3 decimals |
| `error` | Transport-error marker (`timeout`, `connect_failed`, `connection_reset`, `canceled`, `os_error`, `transport`); `""` when a status was received |
| `trace_id` | The `traceparent` trace id this request carried |
| `span_id` | The client-side span id |
| `sampled` | The `traceparent` sampled flag as sent |

Scenario YAML can override any of it:

```yaml
recording:
  enabled: true
  path: /tmp/s1-requests.jsonl
  buffer_records: 65536          # hand-off queue depth; full => drop + count
  flush_interval_seconds: 2
  max_file_megabytes: 512        # at the cap, rename to <path>.1 and start fresh
  trace_sampled_ratio: 1.0       # fraction of traceparents with flags=01
```

**Trace correlation.** The driver is the origin of these requests, so it mints
its own W3C `traceparent` per request (a caller-supplied one is read back
instead of replaced), and the recorded `trace_id` is exactly what the services
received. The sampled flag is set by default and deliberately so: a conformant
service honours an unsampled parent and records no span, which would leave the
trace id pointing at nothing in Jaeger. Java/Python/TypeScript services
extract an incoming `traceparent` today; the Go and Rust runtimes currently
discard it (server-side extraction is issue #419), so until that lands a
recorded id will not find spans for a Go/Rust hop.

**Load neutrality.** The driver is open-loop, so anything that blocks the
event loop delays the dispatcher and silently drops the offered rate below the
configured RPS. A request coroutine therefore does exactly one thing per
record: a non-blocking `put_nowait` of a tuple onto a bounded queue. All
formatting, JSON encoding and file I/O happen on a dedicated writer thread
(CPython releases the GIL inside `write()`). On overflow records are dropped
and counted rather than allowed to back-pressure — reported on the `[records]`
line at shutdown.

### Tests

`request_records.py` is deliberately free of the driver's third-party
dependencies, so its tests run with the standard library alone:

```bash
cd deploy/stress && python3 -m unittest discover -p 'test_*.py' -v
```

