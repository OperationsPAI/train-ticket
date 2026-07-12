# loadgen-go

High-concurrency load generator for the train-ticket system, rewritten from
Python to Go for 50-100x throughput improvement.

## Architecture

The generator uses a **composable provider** pattern: every API interaction is
an independent building-block method on a `Providers` struct. Journeys are
compositions of providers. This keeps journey definitions declarative and each
provider independently testable.

### Key differences from the Python version

| Aspect | Python | Go |
|--------|--------|-----|
| Concurrency model | asyncio + multiprocessing | goroutines |
| Effective parallelism | 32 actors (GIL-bound) | 200+ goroutines |
| HTTP client | httpx AsyncClient | net/http with connection pooling |
| Target RPS | ~16 | 1000+ |

## Dual Mode

The generator supports two scheduling modes configured via `run.mode`:

### closed-loop (default)
Each worker goroutine cycles through journeys with think time between steps
and session pause between journeys. This simulates realistic user behavior.

```yaml
run:
  mode: closed-loop
  workers: 200
  think_time_seconds: { min: 0.5, max: 3.0 }
  session_pause_seconds: { min: 1.0, max: 5.0 }
```

### open-loop
Requests are injected at a fixed rate regardless of response time. Useful for
throughput/latency measurement under controlled load.

```yaml
run:
  mode: open-loop
  target_rps: 500
  ramp_duration_seconds: 30
```

## Usage

### Build

```bash
go build -o loadgen .
```

### Run

```bash
# Default config path
./loadgen

# Custom config
LOADGEN_CONFIG=/path/to/config.yaml ./loadgen
```

### Docker

```bash
docker build -t loadgen-go .
docker run --rm -v /path/to/config.yaml:/etc/loadgen/config.yaml loadgen-go
```

### Kubernetes

Deploy as a single pod (replaces multi-process Python + 4 replicas):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: loadgen
spec:
  replicas: 1
  template:
    spec:
      containers:
      - name: loadgen
        image: loadgen-go:latest
        env:
        - name: LOADGEN_CONFIG
          value: /etc/loadgen/config.yaml
        volumeMounts:
        - name: config
          mountPath: /etc/loadgen
        - name: state
          mountPath: /data
      volumes:
      - name: config
        configMap:
          name: loadgen-config
      - name: state
        emptyDir: {}
```

## Configuration

The Go version reads the same `config.yaml` format as the Python version.
All keys are backward-compatible. New keys:

- `run.mode`: `"closed-loop"` (default) or `"open-loop"`
- `run.target_rps`: target requests per second for open-loop mode
- `run.ramp_duration_seconds`: linear ramp-up duration for open-loop mode

## Journeys

All 15 journey types from the Python version are implemented:

1. **browse** - search + optional quote, then leave
2. **purchase** - full funnel through to ticketing
3. **refund** - post-sales refund case
4. **change** - post-sales change case
5. **fulfillment** - board + complete (or no-show)
6. **support** - open customer service case
7. **legacy** - lifecycle through legacy-acl facade
8. **ride** - dispatch ride request
9. **disruption** - ops disruption recovery drill
10. **transfer** - transfer management drill
11. **loyalty** - check/enroll loyalty tier
12. **insurance** - travel insurance request
13. **group_booking** - group booking with members
14. **corporate** - corporate travel agreement
15. **campaign** - marketing campaign (ops-side)

Plus staff actors (reservation, ticketing, risk review, support, dispatch),
scalper simulation, ops sweep, and bootstrap/schedule-publisher.

## Stats Output

Periodic JSON snapshots are printed to stdout every `stats_interval_seconds`:

```json
{
  "uptime_s": 30.0,
  "journeys": {"purchase:purchased": 42, "browse:browsed": 18, ...},
  "staff_actions": {"reservation": 40, "ticketing": 38, ...},
  "http": {"trip-planning:200": 120, "payment:201": 42, ...},
  "errors": {},
  "latency_ms": {"trip-planning": {"n": 120, "p50": 45.2, "p95": 112.1, "p99": 230.5, "max": 450.0}},
  "scalper": {"attempts": 12, "success": 8, "blocked": 1, "exhausted": 3, "ip_rotations": 24}
}
```
