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
  -f deploy/helm/values-cluster.yaml --namespace train-ticket --wait
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

## Route topology

Trips are drawn from a network of **named lines**, each an ordered list of real
stations at their real cumulative kilometre positions. A trip is a pair of
stations on a common line, so the origin/destination pairs the generator books
are plausible journeys rather than arbitrary pairs.

The table lives in `topology.go`, not in the config file. It is consumed twice
— `bootstrap.go` creates a place, a transport node and a scheduled service per
station pair, and the persona demand model in `demand.go` picks trips out of it
— and a station list duplicated across config and code would drift. Config
selects which lines are active (`bootstrap.lines`) and how much inventory to
create on them (`bootstrap.services_per_date`); the geography is not a knob.

| Line | Stops | Length | Via |
|------|-------|--------|-----|
| `beijing-shanghai` | 16 | 1318 km | Tianjin South, Jinan West, Nanjing South, Suzhou North |
| `beijing-guangzhou` | 16 | 2298 km | Shijiazhuang, Zhengzhou East, Wuhan, Changsha South |
| `shanghai-kunming` | 18 | 2252 km | Hangzhou East, Nanchang West, Changsha South, Guiyang North |
| `hangzhou-shenzhen` | 15 | 1381 km | Ningbo, Wenzhou South, Fuzhou, Xiamen North |

62 stations, **996 plausible directed journeys**. The lines intersect at
Shanghai Hongqiao, Changsha South and Hangzhou East, which is what makes this a
network rather than four disjoint corridors.

### Distance drives fare and duration

Each stop carries its real cumulative kilometre position, so a trip's distance
is a genuine figure and not an ordinal:

- **Fare.** `distanceKm` and `departureTime` are sent on
  `POST /api/v1/fare-quotes`. fare-pricing's default rule set prices the base
  fare at 0.15/km with a 10% discount beyond 500 km, and derives an
  advance-purchase multiplier from the departure time, so the quoted price
  follows the trip instead of being one constant. A trip with no known distance
  (a route the bootstrap sweep discovered rather than created from a line)
  sends neither field, and fare-pricing falls back to its flat base fare.
- **Duration.** A service's arrival time is its departure plus the running time
  at the line's average speed plus dwell at each intermediate stop it passes.
  Two trips of equal length on different parts of a line therefore take
  different times.

### What the domain cannot express

`POST /api/v1/service-segments` refuses any segment whose endpoints are not
exactly its parent scheduled service's endpoints, so a partial segment on a
longer service is a 422: an intermediate stop is **not** expressible as a
bookable sub-segment, and each bookable station pair needs its own scheduled
service. trip-planning is single-leg only, so there are no connecting
itineraries either. Intermediate stops are real in the topology and they
lengthen the journey, but they are not sent, because no field accepts them.

`POST /api/v1/scheduled-services` has no fare, distance or duration field, and
rejects unknown fields outright. Distance reaches the system only through the
fare quote.

## Per-persona demand

Personas differ in **what they travel**, not only in how often they abandon.
Every knob is an ordinary `overrides:` entry read through the behavior context,
so `PersonaConfig` needed no new field.

| Knob | Meaning |
|------|---------|
| `trip_distance_km` | Weight map over the bands `short` (<350 km), `medium` (350-800), `long` (800-1500), `epic` (1500+) |
| `booking_lead_days` | `{min, max}` days ahead of departure this persona books |
| `p_weekend_departure` | Probability the departure falls on a Saturday or Sunday |
| `line_weights` | Weight map over line names |
| `p_repeat_trip` | Probability of re-travelling a pair this persona has already travelled |

Measured over 400 trips per persona against the deployed config:

| Persona | Median distance | Median booking lead | Weekend departures | Distinct pairs per 300 trips |
|---------|-----------------|---------------------|--------------------|------------------------------|
| `business` | 335 km | 2 days | 4% | ~125 |
| `power_user` | 663 km | 8 days | 29% | ~210 |
| `casual` | 1085 km | 19 days | 66% | ~225 |

(Distance, lead and weekend figures are stable; the distinct-pair count varies
by a few percent with the seed, but the ordering does not.)

So a week of records splits cleanly by persona on where and when people
travelled: a business traveller takes short trunk-line hops (>70% of its trips
on `beijing-shanghai` + `beijing-guangzhou`), books one to three days out,
travels on weekdays, and repeats the same hop often enough to visit barely half
as many distinct station pairs as a casual traveller. A casual traveller takes
long trips, books weeks ahead, and mostly departs at a weekend.

`booking_lead_days` must stay inside `bootstrap.departure_window`, which is the
window inventory is created for. A persona booking outside it finds nothing on
its dates.

When a persona's preferred line cannot offer its chosen distance band —
`hangzhou-shenzhen` has no trip over 1381 km, so it can offer no `epic` pair —
the line is redrawn rather than the trip abandoned, because the distance is the
persona's statement about what kind of travel it does. Counted as
`demand:no_pair_in_band:<line>:<band>` in the stats. A registry holding places
outside the topology falls back to a uniform pair, counted as
`demand:uniform_fallback`, and that trip carries no distance.

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

## Outcome records

In addition to (never instead of) the aggregate snapshot above, the generator
writes JSON Lines rows to `recording.path`. Configured under `recording:` in
the ConfigMap; see the annotated block in
`deploy/helm/train-ticket/loadgen-config.yaml`.

The snapshot keeps only a rolling 5000-sample latency buffer per service, so a
spike that has scrolled out of the buffer is unrecoverable and any quantile
nobody asked for before the run cannot be computed afterwards.

**Two record types share the stream**, distinguished by the `type` field:

| `type` | One row per | Answers |
|--------|-------------|---------|
| `request` | Client HTTP request | Which endpoint was slow or returned what status |
| `journey` | Journey attempt | What a simulated user experienced, end to end |

Always select on `type` rather than on which keys are present. Both schemas are
stable rectangles, and a `select(.status == 0)` written for request rows will
silently pick up journey rows whose `status` is a word.

### `type: "request"`

One row per client HTTP request. This makes a finished run re-sliceable by
endpoint, journey or sub-window, and joinable to the server-side spans in
Jaeger by trace id.

```json
{"type":"request","ts":"2026-09-07T07:41:26.472196191Z","chain":"purchase",
 "journey_id":"0190f0ab-1111-7000-8000-000000000042","step":"confirm-order",
 "service":"order","method":"POST","route":"/api/v1/orders/{id}/confirm",
 "path":"/api/v1/orders/0190f0ab-1111-7000-8000-000000000001/confirm","status":201,
 "latency_ms":12.346,"error":"","trace_id":"b0cf9eff63516f81efcd1daaf347da1e",
 "span_id":"d307da84f5b9f9a7","sampled":true}
```

| Field | Meaning |
|-------|---------|
| `type` | Always `request` |
| `ts` | Request **start**, RFC3339 with nanoseconds, UTC |
| `chain` | Journey name, or `staff` / `ops` / `scalper` / `bootstrap` / `schedule` |
| `journey_id` | The journey attempt this request was made under; `""` outside one |
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

```bash
# error rate per route over a sub-window of a finished run
jq -r 'select(.type == "request")
       | select(.ts > "2026-09-07T07:40" and .ts < "2026-09-07T07:45")
       | [.route, (.status|tostring)] | @tsv' requests.jsonl | sort | uniq -c

# the trace ids behind the slowest 10 requests -- paste into Jaeger
jq -s '[.[] | select(.type == "request")] | sort_by(-.latency_ms)[:10]
       | .[] | {latency_ms, route, trace_id}' requests.jsonl

# requests that never got a status
jq -c 'select(.type == "request" and .status == 0)' requests.jsonl
```

### `type: "journey"`

One row per journey attempt, written whatever the outcome.

**A journey can fail without any HTTP request failing.** `waitForResult` blocks
on an in-process channel, so a purchase whose staff reservation never arrives
returns a timeout while every HTTP request it made returned 200. Measured over
one 20-minute window on a fault-injected deployment: 8858 purchase journeys
failed on `timed out waiting for sb`, and the request rows showed a **0% error
rate** — the 58453 `poll-order` requests each took 3.7 ms and each succeeded.
What failed was the journey as a whole. The resident deployment's `purchase`
and `browse` journeys were failing 100% of the time on `available-train`, over
4.1 and 3.0 million attempts, with the same invisibility.

These rows are also the material for generating user-facing issue reports, so
the fields are what a user knows rather than what an engineer would log.

```json
{"type":"journey","ts":"2026-09-07T07:41:12.004881003Z","chain":"purchase",
 "journey":"purchase","persona":"business","status":"failed",
 "outcome":"failed_at_reservation","step":"reservation","steps":11,
 "duration_ms":90341.882,"failure":"timeout","error_shown":false,"http_status":0,
 "detail":"timed out waiting for sb",
 "journey_id":"0190f0ab-1111-7000-8000-000000000042",
 "trace_id":"9f3c1b7e2d4a5061728394a5b6c7d8e9"}
```

| Field | What it lets a complaint say |
|-------|------------------------------|
| `type` | Always `journey` |
| `ts` | Attempt **start**, RFC3339 with nanoseconds, UTC — when they tried |
| `chain` | Same vocabulary as a request row's `chain`, so both types slice alike |
| `journey` | What they were trying to do: `purchase`, `refund`, `change`, `staff_reservation`, … |
| `persona` | Which kind of customer (`casual` / `business` / `power_user`); `""` for staff, scalper, ops, bootstrap and schedule attempts, which have no persona |
| `status` | `completed`, `failed`, `abandoned`, `skipped` or `cancelled` (see below) |
| `outcome` | The journey's own word, unchanged: `purchased`, `abandoned_before_payment`, `waitlist_queued`, `no_show`, or `failed_at_<step>` |
| `step` | Where it stopped — "it failed at payment" against "it failed at search" |
| `steps` | How far through it got: **distinct** steps reached, so a retry loop does not inflate progress |
| `duration_ms` | How long they waited, end to end. The complaint is about the wait |
| `failure` | What kind of failure (see below); `""` when the attempt did not fail |
| `error_shown` | Whether anything readable was displayed. A silent timeout and a refused payment are different complaints |
| `http_status` | The status they were shown; `0` when they were shown none |
| `detail` | The message that came back, truncated to 200 bytes |
| `journey_id` | This attempt's own id — the reference to quote back to support |
| `trace_id` | The trace of the step where it stopped; joins to Jaeger |

`status` is the coarse answer a complaint opens with:

| `status` | Meaning |
|----------|---------|
| `completed` | Ran to its own end. Includes ends a user would not call happy (a no-show, a sold-out search) that the system nonetheless answered fully |
| `failed` | Something they were waiting for never arrived, or arrived as an error |
| `abandoned` | They stopped on purpose, at one of the `behavior.p_abandon_*` branches. Nothing was wrong |
| `skipped` | There was nothing to attempt (no purchase to refund, no route to book). No person experienced anything, so no complaint comes from this row |
| `cancelled` | The run stopped underneath the attempt. An artefact of shutdown, not an experience |

`failure` says what the failure looked like from the user's side. This is
carried structurally rather than recovered by matching substrings of `detail`:

| `failure` | What happened | `error_shown` |
|-----------|---------------|---------------|
| `http_status` | The service answered with a status the caller rejected | `true` |
| `rejected` | The system answered and said no: the order ended `CANCELLED`, the legacy facade refused, every offer attempt was refused | `true` |
| `unavailable` | There was nothing to act on: no trains, no places | `true` |
| `transport` | No response at all; the page did not load | `true` |
| `timeout` | Nothing came back before the wait ran out. **Nothing was displayed** | `false` |
| `unfulfilled` | Accepted, then never completed, with no reason given | `false` |
| `malformed` | Answered successfully, but the answer was unusable (a created resource with no id) | `false` |
| `internal` | The generator itself could not issue the call. Not something a person could have seen | `false` |
| `shutdown` | The run was cancelled under the attempt | `false` |

```bash
# the real journey-level failure rate, which the request rows cannot show
jq -r 'select(.type == "journey") | [.journey, .status] | @tsv' requests.jsonl |
  sort | uniq -c | sort -rn

# what failed, and whether the user was told anything
jq -r 'select(.type == "journey" and .status == "failed")
       | [.journey, .step, .failure, (.error_shown|tostring)] | @tsv' requests.jsonl |
  sort | uniq -c | sort -rn

# the longest waits that ended in nothing -- the loudest complaints
jq -s '[.[] | select(.type == "journey" and .status == "failed")]
       | sort_by(-.duration_ms)[:10]
       | .[] | {duration_ms, journey, persona, step, failure, detail}' requests.jsonl

# journeys that failed with every one of their requests returning 200:
# the exact blind spot this record type closes
jq -s 'map(select(.type == "journey" and .status == "failed") | .journey_id) as $bad
       | [.[] | select(.type == "request" and (.journey_id | IN($bad[])))]
       | group_by(.journey_id)
       | map(select(all(.[]; .status >= 200 and .status < 400)) | .[0].journey_id)
       | length' requests.jsonl

# every request a failed journey made, in order -- the technical record
# behind one complaint
jq -c 'select(.type == "request" and .journey_id == "<id from a journey row>")
       | {ts, step, service, status, latency_ms, trace_id}' requests.jsonl
```

Journey rows replaced the generator's unstructured failure output. There is no
longer a `[cust] ... failed`, `[staff%d] ... failed`, `[scalper%d] ... failed`
or `[ops] failed` line: those carried no timestamp, no duration, no step and no
trace id, and were 3.6% of loadgen output in a fault-injected namespace and
22.6% in the fault-free reference. Every one of them was a failed or partly
failed journey. Process-lifecycle lines (`[stats]`, `[final]`, `[recorder]`)
are unchanged.

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
