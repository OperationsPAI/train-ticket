# Observability Baseline

This repository uses OpenTelemetry as the standard collection contract for
runtime telemetry. The current baseline provides a local collector and a stable
environment contract that every service runtime can target without making tests
depend on external infrastructure.

## Collection Topology

`platform/observability/otel-collector.yaml` defines the local collector:

- OTLP gRPC receiver on `4317`.
- OTLP HTTP receiver on `4318`.
- health extension on `13133`.
- zPages extension on `55679`.
- debug exporters for traces, metrics, and logs.
- Jaeger OTLP exporter for traces, targeting `jaeger:4317`, with retry and a
  bounded sending queue so a trace-store restart does not drop spans.

The debug exporters are retained for local smoke checks and low-level signal
flow visibility. Traces are also exported to Jaeger, which persists them to disk
with an explicit retention window — see
[Trace Retention and Storage](#trace-retention-and-storage). Production
deployment can replace or extend the exporters while preserving the same
service-side OTLP contract.

## Local Runtime

Start the collector from the repository root:

```bash
make observability-up
```

Validate compose rendering without starting containers:

```bash
make observability-config
```

Validate the collector configuration with the collector binary:

```bash
make observability-validate
```

Stop the local collector:

```bash
make observability-down
```

The compose file lives at `platform/observability/docker-compose.yaml` and starts
the OpenTelemetry Collector, Jaeger with its persistent Badger store, and a
one-shot `jaeger-init` service that prepares the volume's ownership (the compose
equivalent of the pod `securityContext.fsGroup` used in kind, since the jaeger
image runs as uid 10001).

## kind Cluster Deployment

The kind overlay in `deploy/k8s/` includes an `otel-collector` Deployment,
ClusterIP Service, and ConfigMap in the `train-ticket` namespace. The ConfigMap
is copied from `platform/observability/otel-collector.yaml`, which remains the
canonical collector configuration for both compose and kind. The kind manifest
pins a collector-contrib image tag so cluster rollouts are reproducible; update
that tag deliberately when advancing the local baseline.

Apply the stack from the repository root:

```bash
kubectl apply -k deploy/k8s
kubectl -n train-ticket rollout status deploy/otel-collector
```

The collector listens on the same ports as the local baseline:

- OTLP gRPC: `otel-collector:4317` inside the cluster.
- OTLP HTTP: `otel-collector:4318` inside the cluster.
- Health check: `otel-collector:13133`.
- zPages: container port `55679` for direct pod access or port-forwarding.

Verify the health extension from a local shell with port-forwarding:

```bash
kubectl -n train-ticket port-forward svc/otel-collector 13133:13133
curl -fsS http://127.0.0.1:13133/
```

To confirm spans arrive after a service SDK/exporter is wired in by a later
REQ-096/097/098 task, watch the debug exporter output:

```bash
kubectl -n train-ticket logs deploy/otel-collector -f
```

Seeing no spans immediately after this infrastructure change is expected: the
collector and service-side environment contract are present, but service SDK
exporters are not installed by this task.

## Jaeger Trace Querying

Jaeger all-in-one is the trace store for both compose and kind. It accepts
collector-exported OTLP/gRPC traces on the in-cluster `jaeger:4317` service port
and serves the Jaeger UI on port `16686`. The service-side contract does not
change: services still send OTLP to the collector through the standard `OTEL_*`
environment variables.

Access the UI from a kind cluster with port-forwarding:

```bash
kubectl -n train-ticket port-forward svc/jaeger 16686:16686
```

Open <http://127.0.0.1:16686>, select `journey-order` in the Service field, and
run a search after generating a purchase flow. The same query can be checked via
the Jaeger API:

```bash
curl -fsS 'http://127.0.0.1:16686/api/traces?service=journey-order&limit=5'
```

A successful purchase-chain sample returns at least one trace in the `data`
array.

Two query shapes are supported and both are exercised by the verification
procedure below:

```bash
# by trace id
curl -fsS "http://127.0.0.1:16686/api/traces/${TRACE_ID}"

# by (service, time range) -- start/end are UNIX microseconds
NOW=$(date +%s)
curl -fsS "http://127.0.0.1:16686/api/traces?service=journey-order\
&start=$(( (NOW-3600) * 1000000 ))&end=$(( NOW * 1000000 ))&limit=20"
```

## Trace Retention and Storage

Traces are **durable**. The store is Jaeger's embedded
[Badger](https://github.com/dgraph-io/badger) backend writing to a
PersistentVolumeClaim in kind (`jaeger-badger`, 10Gi) and to a named Docker
volume in compose (`train-ticket-jaeger-badger`). Both are configured in
`deploy/k8s/jaeger.yaml` and `platform/observability/docker-compose.yaml`, and
both are on by default: `kubectl apply -k deploy/k8s` and `make observability-up`
each stand up a persistent store with no extra flags.

### Retention window: 12 hours

Set explicitly via `--badger.span-store-ttl=12h`. Badger's maintenance thread
(`--badger.maintenance-interval=5m`) drops spans older than the window; a query
for an expired trace id returns HTTP 404 `trace not found`.

The window comes straight from the issue requirement of "one full stress
scenario plus a comparable window before it". The longest run recorded in
`docs/09-performance/stress-test-report-2026-07-12.md` is the 6+ hour
long-running stability soak, so 6h of scenario + 6h of prior context = **12h**.
That also means a run started any time yesterday evening is still readable the
next morning, and both halves of an A/B comparison survive together.

In compose the window is overridable with `JAEGER_SPAN_STORE_TTL` (see
`platform/observability/env.example`); in kind, edit the arg in
`deploy/k8s/jaeger.yaml`. Raising it without also growing the volume moves the
binding constraint from the TTL to the disk — see the table below.

### Why Badger, and not ClickHouse or Elasticsearch

All three are viable with the images already pinned in this repo. Badger wins
for a single-node kind development cluster:

- **No new component.** Badger ships *inside* `jaegertracing/all-in-one:1.57`.
  Selecting it is `SPAN_STORAGE_TYPE=badger` plus a volume. ClickHouse or
  Elasticsearch each add a container, an image pull, a readiness dependency, and
  schema/index bootstrap to a node already running 38 business services and 6
  infrastructure pods.
- **Cheaper.** An Elasticsearch single node wants ≥1GB of JVM heap before it
  stores anything, plus an `es-index-cleaner` CronJob to implement retention.
  Badger's retention is one flag.
- **Native retention.** `--badger.span-store-ttl` is a first-class TTL.
  Elasticsearch needs the external cleaner job; ClickHouse needs a `TTL` clause
  in DDL that has to be kept in sync by hand.
- **Operational simplicity over horizontal scale**, which is the right trade for
  a local dev cluster. The cost is real and accepted: Badger is single-writer,
  so the Deployment must stay `replicas: 1` with `strategy: Recreate` (Badger
  holds an exclusive `LOCK` file on its directory, and the PVC is
  ReadWriteOnce). A `RollingUpdate` would crash-loop the new pod until the old
  one exited. If this stack is ever pointed at a multi-node cluster that needs
  concurrent readers/writers or retention in weeks rather than hours, revisit
  this decision — that is what ClickHouse/Elasticsearch are for.
- The PVC follows the idiom already in the repo: `storageClassName: standard`,
  `ReadWriteOnce`, as in `deploy/k8s/postgres.yaml`.

### Capacity arithmetic

The sizing is derived from measured values, not estimates. The two per-span
constants were measured against this repo's actual span shape:

| Measured input | Value | How |
|---|---|---|
| Spans per purchase-chain trace | **5.32** mean (max 14) | 200 live `journey-order`-rooted traces read from the running store |
| Span payload | **1.05 KB** JSON mean | same sample |
| **On-disk cost per span** | **1.53 KiB** → plan at **1.6 KiB** | loaded 200,000 spans carrying the repo's real attribute set (14 span + 11 resource attributes) into Badger; `du -sk` reported 306,016 KiB allocated after compaction settled |
| Jaeger+Badger memory | **770 MiB** steady, **1.76 GiB** peak | measured after / during that 200k-span ingest |

Span rate, from `docs/09-performance/stress-test-report-2026-07-12.md`:

- Phase 3 records **35 RPS** (10 loadgen pods) up to **220 RPS** (80 pods).
  Those figures count loadgen-issued edge requests.
- Not every edge request roots a 5.32-span trace — polls and browse journeys
  produce single-span traces — so the blended figure used is **3 spans per edge
  request**.
- The default is sized for **35 RPS**, the lowest full-scenario rate in the
  report and a realistic ceiling for one kind node. The 220 RPS peak was
  measured on a *20-node* Volces VKE cluster; `docs/09-performance/baseline-report.md`,
  run on a single-node kind cluster, reached ~1 RPS. Sizing the dev default for
  220 RPS would be sizing for load this node cannot generate.

```
  35 RPS x 3 spans/request              =    105 spans/s
 105 spans/s x 1.6 KiB/span             =    168 KiB/s   = 0.58 GiB/h
0.58 GiB/h x 12 h retention             =    6.9 GiB
                             PVC        =     10Gi        (~45% headroom)
```

The headroom absorbs Badger's LSM transients: compaction rewrites SST files
before dropping the originals, and a 32 MiB memtable is preallocated on disk.

Total span capacity in the window: `105 spans/s x 43,200 s` = **~4.5M spans**,
against `20,000 traces x 5.32` = ~106,000 spans for the previous in-memory
store — roughly a **43x** increase, and now durable. For contrast, the old
20,000-trace cap filled in about **4.6 minutes** at 220 RPS
(`20000 / (220/3 traces/s)`) and about 28 minutes at 35 RPS, which is what the
issue meant by "a few minutes".

### Scaling the volume for higher rates

If you point this stack at a cluster that can actually sustain the higher
documented rates, the 12h window needs proportionally more disk:

| Edge RPS | Spans/s | Disk rate | 12h window | Volume to provision | 10Gi holds |
|---------:|--------:|----------:|-----------:|--------------------:|-----------:|
| 35 (default) | 105 | 0.58 GiB/h | 6.9 GiB | 10Gi | 12h (TTL binds) |
| 103 | 309 | 1.70 GiB/h | 20.4 GiB | 25Gi | ~5.9h |
| 220 (peak) | 660 | 3.63 GiB/h | 43.5 GiB | 50Gi | ~2.8h |

At 10Gi and 220 RPS the **disk**, not the TTL, becomes the binding limit at
~2.8h. Either grow the volume or shorten the TTL; do not leave the two
inconsistent.

> **kind caveat.** The `standard` StorageClass in kind is
> `rancher.io/local-path`, which is hostPath-backed and does **not** enforce the
> 10Gi request — it creates a directory under
> `/var/local-path-provisioner/` on the node. The 10Gi figure documents intent
> and is enforced on any cluster with a real provisioner; on kind the TTL is the
> only hard bound, so a badly-raised TTL will consume node disk. Check headroom
> with `docker exec <kind-node> df -h /`.

### Memory limit

The pod's memory limit was raised from **256Mi to 2Gi** (request 512Mi), and
`GOMEMLIMIT=1750MiB` makes the Go runtime collect before the kubelet OOM-kills
the container.

256Mi was not survivable, and not merely tight: Badger's logged startup
configuration allocates a **256 MiB block cache** plus up to **5 x 64 MiB
memtables** — 576 MiB of buffers before a single span is stored. The measured
figures above (770 MiB steady, 1.76 GiB under burst ingest) set the 2Gi limit.
This also resolves the eviction-before-the-cap problem the issue describes: the
old pod could be reclaiming memory long before reaching 20,000 traces.

### Export reliability across a store restart

Because the store is now a stateful component that goes offline for a few
seconds when its pod is replaced, the collector's `otlp/jaeger` exporter is
configured with `retry_on_failure` (1s to 30s backoff, 5m ceiling) and a bounded
in-memory `sending_queue` (4,000 batches). Spans emitted while the store is
restarting are redelivered rather than dropped.

The queue is in memory, so this covers a store restart, not a *collector*
restart. Making that lossless too would need the `file_storage` extension and a
second PVC for the collector; that is deliberately out of scope here.

### Verifying persistence end to end

With the workload stopped:

```bash
# 1. note a trace id that exists
kubectl -n train-ticket port-forward svc/jaeger 16686:16686 &
TRACE_ID=$(curl -fsS 'http://127.0.0.1:16686/api/traces?service=journey-order&limit=1' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"][0]["traceID"])')
echo "$TRACE_ID"

# 2. destroy the trace store pod
kubectl -n train-ticket delete pod -l app.kubernetes.io/name=jaeger
kubectl -n train-ticket rollout status deploy/jaeger

# 3. query that trace back by id -- expect one trace, not HTTP 404
kubectl -n train-ticket port-forward svc/jaeger 16686:16686 &
curl -fsS "http://127.0.0.1:16686/api/traces/${TRACE_ID}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("traces:",len(d),"spans:",len(d[0]["spans"]))'

# 4. and by (service, time range)
NOW=$(date +%s)
curl -fsS "http://127.0.0.1:16686/api/traces?service=journey-order\
&start=$(( (NOW-3600) * 1000000 ))&end=$(( NOW * 1000000 ))&limit=20"
```

The same check in compose, which does not require a cluster:

```bash
make observability-up
# ... submit a trace to localhost:4318, note its id ...
docker restart train-ticket-observability-jaeger-1
curl -fsS "http://127.0.0.1:16686/api/traces/${TRACE_ID}"
```

Deleting the volume is the only way to lose traces:

```bash
kubectl -n train-ticket delete pvc jaeger-badger   # kind
docker volume rm train-ticket-jaeger-badger        # compose
```

## Metrics and Logs Are Still Not Retained

Traces are durable; **metrics and logs are not**. The collector defines all
three pipelines, but only `traces` has a real exporter. `metrics` and `logs`
still export to `debug`, which writes to the collector's own stdout and is not
queryable, and every instrumented Deployment sets `OTEL_METRICS_EXPORTER=none`
and `OTEL_LOGS_EXPORTER=none` so those pipelines receive nothing anyway.

Closing that gap is out of scope for the trace-retention work and needs, at
minimum:

1. A metrics store and a real exporter (e.g. a Prometheus deployment plus
   `prometheus` / `otlphttp` exporter on the `metrics` pipeline), and a log store
   plus exporter (e.g. Loki via `loki` exporter) on the `logs` pipeline — in both
   `platform/observability/otel-collector.yaml` and the mirrored ConfigMap in
   `deploy/k8s/otel-collector.yaml`.
2. Their own PVCs and retention windows, sized the same way as above.
3. Flipping `OTEL_METRICS_EXPORTER` / `OTEL_LOGS_EXPORTER` from `none` to `otlp`
   on the service Deployments in `deploy/k8s/services.yaml`, plus the equivalent
   change to the documented service contract below, and SDK-side metric/log
   provider wiring in each language runtime baseline (the runtime adapters
   currently install trace APIs only).

Until then, treat `docs/09-performance/` reports as the record for metrics.

## Service Contract

Every service should use the same baseline environment shape when real
OpenTelemetry SDK instrumentation is enabled. The initial rollout enables only
trace export; metrics and logs stay disabled until a follow-up task installs and
configures those SDK pipelines.

```bash
OTEL_SERVICE_NAME=<service-id>
OTEL_RESOURCE_ATTRIBUTES=service.namespace=train-ticket,deployment.environment=local
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

Inside the kind cluster, all 23 business services receive the same standard
OpenTelemetry variables from `deploy/k8s/services.yaml`:

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
OTEL_SERVICE_NAME=<service-name>
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

When these environment variables are missing, services must run directly with
zero telemetry overhead. Unit tests, local binaries, and development scenarios
without a collector must continue to use no-op OpenTelemetry APIs and must not
attempt network export.

For OTLP/HTTP local experiments, target `http://localhost:4318` and set the
runtime-specific standard protocol option (for example
`OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf`) only in that local environment.

## Runtime Adapters

Language runtime baselines keep OpenTelemetry optional by default and now include
OpenTelemetry API adapters that bind to the existing runtime seams:

- Go services call `goruntime.ObserverFromEnv(serviceID)`. It returns the no-op
  observer unless `OTEL_TRACES_EXPORTER` is set to a value other than `none`;
  when enabled it starts server spans with the global OpenTelemetry tracer.
- Python FastAPI services accept an optional `otel_tracer` on `create_app()` and
  can discover `opentelemetry.trace.get_tracer()` from `OTEL_TRACES_EXPORTER`
  when the optional OpenTelemetry API package is installed.
- TypeScript Fastify services export `opentelemetryInstrumentationFromEnv()` to
  adapt an OpenTelemetry API tracer to `createApp({ startSpan })`; without an
  injected tracer or enabled exporter it returns empty hooks.
- Java Spring services provide `OpenTelemetryRuntimeTracer`, activated only when
  `otel.traces.exporter=otlp` / `OTEL_TRACES_EXPORTER=otlp` is selected;
  `NoOpRuntimeTracer` remains the default bean.
- Rust Axum-compatible modules use `OpenTelemetryObserver::from_env(service_id)`
  from `platform/shared-kernel-rust`, returning `NoopObserver` unless tracing is
  explicitly enabled.

The adapters use only OpenTelemetry API surfaces in the runtime baseline. A
service deployment that needs real export must install/configure the language
SDK and OTLP exporter in bootstrap or packaging, using the `OTEL_*` contract
above. With no SDK provider installed, API spans are non-recording and tests do
not require a collector.

All HTTP server spans/events include service name where available, HTTP
method/path/status, request ID, and correlation ID using stable attributes such
as `service.name`, `http.request.method`, `url.path`,
`http.response.status_code`, `http.request_id`, and `http.correlation_id` (with
legacy `http.method` / `http.status_code` aliases during the baseline).

## Rules

- No service test may require a running collector.
- Request and correlation IDs must be included in emitted trace attributes.
- `/metadata` should disclose that tracing is opt-in and no-op by default until
  a runtime adapter is installed.
- Collector exporter changes must keep OTLP HTTP and gRPC receiver ports stable
  unless all service deployment templates are updated in the same change.
- The trace-store Deployment must stay `replicas: 1` with `strategy: Recreate`,
  and its memory limit must stay well above 576Mi. Badger holds an exclusive
  directory lock and preallocates that much buffer before storing any span.
- The retention window (`--badger.span-store-ttl`) and the volume size must be
  changed together, using the arithmetic in
  [Capacity arithmetic](#capacity-arithmetic).
