# loadgen-go

High-concurrency load generator for the train-ticket system. This is the
**only** load generator in the repo — it replaced an earlier Python
implementation (`deploy/loadgen/`, removed) and is what
`train-ticket/loadgen:local` builds from.

## Architecture

The generator uses a **composable provider** pattern: every API interaction is
an independent building-block method on a `Providers` struct. Journeys are
compositions of providers. This keeps journey definitions declarative and each
provider independently testable.

A single process runs the whole actor population on goroutines: customer
workers (`run.workers`), staff workers (`staff.workers`), scalper workers,
the ops sweep, and the schedule publisher, all sharing one HTTP connection
pool and one in-memory entity registry. There is no process fan-out — scale
by raising `run.workers`, not by adding replicas.

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

### Build and test

This module is a member of the repository's root `go.work`, so the ordinary
commands work with no environment setup:

```bash
go build ./...
go test ./...
```

(The image build resolves the module standalone with `GOWORK=off`, since
`go.work` is not part of the Docker context. The module's `go.mod` pins the
same dependency versions the workspace resolves, so both paths build the same
code.)

### Run locally

```bash
LOADGEN_CONFIG=../helm/train-ticket/loadgen-config.yaml go run .
```

`LOADGEN_CONFIG` defaults to `./config.yaml` if unset. Note that
`deploy/loadgen-go/config.yaml` is a **local development sample**; the config
that actually runs in the cluster is
`deploy/helm/train-ticket/loadgen-config.yaml`.

### Docker / Kubernetes

The image is built from `deploy/docker/loadgen/Dockerfile` (repo-root build
context, consistent with every other service) by `deploy/build-images.sh`:

```bash
deploy/build-images.sh          # builds + kind-loads train-ticket/loadgen:local
helm upgrade --install train-ticket deploy/helm/train-ticket \
  -f deploy/helm/values-kind.yaml --namespace train-ticket --wait
```

Or `make deploy` for the whole pipeline, `make deploy-fast` to skip the rebuild.

The Deployment lives in `deploy/helm/train-ticket/templates/loadgen.yaml`.
Configuration is supplied by the `loadgen-config` ConfigMap, which the chart
renders from `deploy/helm/train-ticket/loadgen-config.yaml` and also hashes into
the pod's `checksum/config` annotation — so editing that file and re-running
`helm upgrade` changes the pod template and rolls the pod automatically. (Helm
needs that annotation explicitly; kustomize used to get the same effect for free
from its ConfigMap name hash.) No config is baked into the image.

Sizing knobs that are *not* in the config file live in values under `loadgen`:
`replicas`, `gomaxprocs`, `resources` and `stateSizeLimit` (the `/data` emptyDir
cap). Customer concurrency is `run.workers` in the config file, not a value —
the template has no way to inject it into the mounted file.

## Configuration

Schema reference: `config.go`. Decoding is **non-strict** — an unrecognized or
misspelled key is silently ignored rather than rejected, so it lands as a zero
value and the knob goes quietly dead. `deployed_config_test.go` guards against
this for the deployed ConfigMap: it strict-decodes
`deploy/helm/train-ticket/loadgen-config.yaml`, asserts the load-bearing fields
survive decoding, and fails if a `behavior.*` / `long_tail.*` / `defaults.*` key
exists that no Go code reads.

That guard now passes with a single documented exception:
`defaults.insurance_premium_minor`. It is inert in both this implementation and
the Python one it replaced — travel-insurance's `POST /api/v1/policies` takes no
premium field — so it is allowlisted in the test rather than deleted, as
config-level documentation of intent. The `long_tail` and `wallet_promotion`
sections *are* consumed (`providers.go` gates the read probes on
`long_tail.enabled`, and `journey_wallet.go` reads both `wallet_promotion` knobs).

## Journeys

All 15 journey types are implemented:

1. **browse** - search + optional quote, then leave
2. **purchase** - full funnel through to ticketing
3. **refund** - post-sales refund case
4. **change** - post-sales change case
5. **fulfillment** - board + complete (or no-show)
6. **support** - open customer service case
7. **legacy** - lifecycle through legacy-acl facade
8. **ride** - dispatch ride request
9. **disruption** - ops disruption recovery drill
10. **transfer** - transfer management drill (seeds its own place-network
    STATION place + transport node once per process, since transfer-management
    validates connection node refs against place-network)
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

## Per-request records

In addition to (never instead of) the aggregate snapshot above, the generator
writes **one JSON Lines row per client HTTP request** to `recording.path`.
Configured under `recording:` in the ConfigMap; see the annotated block in
`deploy/helm/train-ticket/loadgen-config.yaml`.

The snapshot keeps only a rolling 5000-sample latency buffer per service, so a
spike that has scrolled out of the buffer is unrecoverable and any quantile
nobody asked for before the run cannot be computed afterwards. One row per
request makes a finished run re-sliceable by endpoint, journey or sub-window,
and joinable to the server-side spans in Jaeger by trace id.

```json
{"ts":"2026-09-07T07:41:26.472196191Z","chain":"purchase","step":"confirm-order",
 "service":"order","method":"POST","route":"/api/v1/orders/{id}/confirm",
 "path":"/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm","status":201,
 "latency_ms":12.346,"error":"","trace_id":"b0cf9eff63516f81efcd1daaf347da1e",
 "span_id":"d307da84f5b9f9a7","sampled":true}
```

| Field | Meaning |
|-------|---------|
| `ts` | Request **start**, RFC3339 with nanoseconds, UTC |
| `chain` | Journey name, or `staff` / `ops` / `scalper` / `bootstrap` / `schedule` |
| `step` | Call-site step label (same value as the `StepError.Step`) |
| `service` | Target service |
| `method` | HTTP method |
| `route` | Route template — id-looking segments collapsed to `{id}`, query dropped |
| `path` | Raw requested path, query string included |
| `status` | HTTP status; **`0` means no response was ever received** |
| `latency_ms` | Wall time around `client.Do`, 3 decimals |
| `error` | Transport-error marker (`timeout`, `connect_failed`, `connection_reset`, `read_failed`, `write_failed`, `canceled`, `transport`); `""` when a status was received |
| `trace_id` | The `traceparent` trace id this request carried |
| `span_id` | The client-side span id |
| `sampled` | The `traceparent` sampled flag as sent |

Every field is always present, so the file is a stable rectangle:

```bash
# error rate per route over a sub-window of a finished run
jq -r 'select(.ts > "2026-09-07T07:40" and .ts < "2026-09-07T07:45")
       | [.route, (.status|tostring)] | @tsv' requests.jsonl | sort | uniq -c

# the trace ids behind the slowest 10 requests -- paste into Jaeger
jq -s 'sort_by(-.latency_ms)[:10] | .[] | {latency_ms, route, trace_id}' requests.jsonl

# requests that never got a status
jq -c 'select(.status == 0)' requests.jsonl
```

### Trace correlation

The generator is the **origin** of these requests, so it mints its own
W3C `traceparent` (`00-<32 hex>-<16 hex>-01`) per request rather than waiting
for an instrumented client; a caller-supplied `traceparent` is read back
instead of replaced. `trace_id` in the record is therefore exactly the id the
services received.

The sampled flag is set (`01`) by default, deliberately: a conformant service
honours an unsampled parent and records no span, which would leave the
recorded trace id pointing at nothing in Jaeger. Lower
`recording.trace_sampled_ratio` only to shed backend volume — rows then carry
`sampled: false` so an analysis can distinguish "no span was ever recorded"
from "the span is missing".

Java, Python and TypeScript services extract an incoming `traceparent` today,
so those spans join immediately. The Go and Rust runtimes currently discard it
and start a new trace; server-side extraction for them is issue #419, and
until that lands a recorded trace id will not find spans for a Go/Rust hop.

### Load neutrality

Recording must not change the offered load, so a request goroutine does
exactly one thing per record: a **non-blocking** send of a value struct onto a
buffered channel. Timestamp formatting, route templating, JSON encoding,
buffered writes and rotation all happen on one dedicated writer goroutine, so
disk latency can never back-pressure into the offered RPS. When the buffer is
full records are **dropped and counted** — a quantified hole in the file beats
an invisible dent in the load profile. Drops are reported on the `[recorder]`
line and at shutdown.

### Durability

The file is opened `O_APPEND` (never truncated), flushed every
`recording.flush_interval_seconds`, and rotated to `<path>.1` at
`recording.max_file_megabytes`. In the cluster it lives on the `/data`
emptyDir declared in `deploy/helm/train-ticket/templates/loadgen.yaml` (capped by
`loadgen.stateSizeLimit`), so it survives container
restarts but not pod deletion — copy it out first:

```bash
kubectl -n train-ticket cp \
  "$(kubectl -n train-ticket get pod -l app.kubernetes.io/name=loadgen \
      -o jsonpath='{.items[0].metadata.name}')":/data/requests.jsonl \
  ./requests.jsonl
```
