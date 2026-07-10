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

The auditor writes a JSON audit report with pass/fail for each assertion.
