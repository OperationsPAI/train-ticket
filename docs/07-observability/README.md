# Observability Baseline

This repository uses OpenTelemetry as the standard collection contract for
runtime telemetry. The current baseline provides a local collector and a stable
environment contract that every service runtime can target without making tests
depend on external infrastructure.

## Collection Topology

Every signal — traces, logs and metrics — is collected by OpenTelemetry
Collectors and stored in ClickHouse. There are three collectors in the cluster,
split by what a receiver can physically see rather than by preference:

| Collector | Workload | Receivers | What it is the only source of |
| --- | --- | --- | --- |
| `otel-collector` | Deployment | `otlp` (4317 gRPC, 4318 HTTP) | Application traces. Every service points `OTEL_EXPORTER_OTLP_ENDPOINT` here. |
| `otel-agent` | DaemonSet | `filelog`, `kubeletstats`, `hostmetrics` | Application logs, per-pod/node resource usage. |
| `otel-cluster` | Deployment (1 replica) | `k8s_cluster`, `k8sobjects`, `prometheus` | Workload state, Kubernetes events, federated Prometheus metrics. |

`filelog` must run per-node because `/var/log/pods` is node-local; `k8s_cluster`
must run exactly once because N replicas would emit N copies of the same cluster
state. That is the whole reason for the split.

`platform/observability/otel-collector.yaml` defines the OTLP gateway, and is
the canonical config that the `otel-collector-config` ConfigMap in
`deploy/helm/train-ticket/templates/observability.yaml` mirrors. It carries:

- OTLP gRPC receiver on `4317`, OTLP HTTP on `4318`.
- health extension on `13133`, zPages on `55679`.
- a `clickhouse` exporter on the traces, metrics and logs pipelines, with retry
  and a bounded sending queue so a store restart does not drop signals.

The agent and cluster collectors have no compose equivalent — their receivers
read node-local and cluster-scoped state that does not exist outside a cluster.

### Where logs come from, and why not the SDK

The services set `OTEL_LOGS_EXPORTER=none` and `OTEL_METRICS_EXPORTER=none`, and
the language kits install trace providers only. Logs are therefore collected by
tailing the files the kubelet already writes, not over OTLP. This is deliberate:
it makes every service's stdout queryable — including init containers, which
have no SDK at all — without touching 38 services or the kit wiring.

The cost is that a log record's structure is whatever the service printed. The
services log plain text, and trace correlation rides in a bracketed prefix —
`[trace=<id> span=<id>]`, rendered from the MDC by
`LOGGING_PATTERN_CORRELATION` in `templates/services.yaml`. The agent's filelog
operators parse the ids out of that prefix into `TraceId`/`SpanId`, which is what
makes the log-to-span join work. Records without the prefix — the Python
services, init containers, Spring's startup banner — are kept with an empty
`TraceId` rather than dropped.

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
the OpenTelemetry Collector and ClickHouse. Only the OTLP pipeline exists there;
the pod-log and Kubernetes receivers that the cluster runs have no meaning under
compose.

## kind Cluster Deployment

The Helm chart renders the whole stack into the `train-ticket` namespace:

| Template | Renders |
| --- | --- |
| `templates/observability.yaml` | The `otel-collector` OTLP gateway (Deployment, Service, ConfigMap) and Mailpit. |
| `templates/otel-agent.yaml` | The `otel-agent` DaemonSet and its config. |
| `templates/otel-cluster.yaml` | The `otel-cluster` Deployment and its config. |
| `templates/otel-rbac.yaml` | One ServiceAccount, ClusterRole and binding shared by all three collectors. |
| `templates/clickhouse.yaml` | The ClickHouse StatefulSet, Service and volume. |
| `templates/prometheus.yaml` | Prometheus, kube-state-metrics and node-exporter. |

The gateway's ConfigMap data is copied from
`platform/observability/otel-collector.yaml`, which remains the canonical
collector configuration for both compose and kind. The tunable numbers in the
copy are substituted from `.Values.otelCollector`
(`otelCollector.memoryLimitMiB`, `otelCollector.sendingQueue.*`), so those are
the knobs to turn rather than the template body. `otelCollector.image.tag` pins a
collector-contrib image tag so cluster rollouts are reproducible; update that tag
deliberately when advancing the local baseline.

Three blocks are shared between the collectors through named templates in
`templates/_helpers.tpl` — the `clickhouse` exporter, the `k8sattributes`
processor, and the `service.namespace` backfill. They are defined once because a
divergence between the three would be invisible: each collector would keep
working and write somewhere slightly different, surfacing only as a query
returning fewer rows than expected.

Each collector hashes its **rendered config** into the pod's `checksum/config`
annotation, so any change that reaches the collector — a value or the template
body — rolls the workload. Without that, a `helm upgrade` would leave the running
collector on the old config until someone restarted it by hand; kustomize used to
get this for free from its ConfigMap name hash. Hashing `.Values` instead is not
enough, and was the actual bug: it misses an edit to the config body, so the
ConfigMap updates while the pod keeps running the old bytes.

Apply the stack from the repository root:

```bash
helm upgrade --install train-ticket deploy/helm/train-ticket \
  -f deploy/helm/values-kind.yaml \
  --namespace train-ticket --create-namespace --wait
kubectl -n train-ticket rollout status deploy/otel-collector
```

`make deploy` (or `make deploy-fast`, which skips the image rebuild) runs that
install as one step of the full pipeline; see `deploy/README.md`.

The gateway listens on the same ports as the local baseline:

- OTLP gRPC: `otel-collector:4317` inside the cluster.
- OTLP HTTP: `otel-collector:4318` inside the cluster.
- Health check: `otel-collector:13133`.
- zPages: container port `55679` for direct pod access or port-forwarding.

Verify the health extension from a local shell with port-forwarding:

```bash
kubectl -n train-ticket port-forward svc/otel-collector 13133:13133
curl -fsS http://127.0.0.1:13133/
```

`deploy/e2e/13-observability.sh` asserts this end to end: health, zPages, and
that `otelcol_receiver_accepted_spans` grows after real traffic.

### RBAC is load-bearing

The collectors need pod, namespace and replicaset read access for the
`k8sattributes` processor, `nodes/stats` for `kubeletstats`, and **`nodes/metrics`
for the Prometheus kubelet and cAdvisor scrapes**. That last one is easy to get
wrong: the kubelet authorizes `/metrics` and `/metrics/cadvisor` against
`nodes/metrics` specifically, and `nodes/proxy` is not a substitute. Leaving it
out returns 403 on exactly those two Prometheus targets while every other target
stays green, so nothing looks broken and `container_*` metrics are simply absent.

The failure mode of a missing `k8sattributes` rule is quieter still: the tables
fill up normally, but the rows carry no `k8s.namespace.name` or `service.name`,
so every query that filters on them returns nothing. If a query comes back empty
against a store that is clearly growing, check this first.

Both classes of failure are worth checking directly rather than inferring:

```bash
kubectl -n train-ticket exec deploy/prometheus -- \
  wget -qO- 'http://localhost:9090/api/v1/targets?state=active' | grep -o '"health":"[a-z]*"' | sort | uniq -c
```

## Querying Telemetry

ClickHouse is reachable in-cluster on `clickhouse:9000` (native) and
`clickhouse:8123` (HTTP). Open a client:

```bash
kubectl -n train-ticket exec -it clickhouse-0 -- \
  clickhouse-client --password trainticket-otel --database otel
```

The exporter creates and owns these tables:

| Table | Holds | Time column |
| --- | --- | --- |
| `otel_traces` | Spans. Plus the `otel_traces_trace_id_ts` materialized view, which is what makes lookup by trace id fast. | `Timestamp` |
| `otel_logs` | Pod logs (from `filelog`) and Kubernetes events (from `k8sobjects`). | `Timestamp` |
| `otel_metrics_gauge`, `_sum`, `_histogram`, `_exponential_histogram`, `_summary` | One table per metric type, all derived from the `metrics_table_name` prefix. | `TimeUnix` |

**Traces and logs filter on `Timestamp`; metrics filter on `TimeUnix`.** Using
the wrong one is a column-not-found error on metrics, which is at least loud.

A trace by id, and the slowest recent operations per service:

```sql
SELECT Timestamp, ServiceName, SpanName, Duration / 1e6 AS ms, StatusCode
FROM otel_traces
WHERE TraceId = 'a1b2c3...'
ORDER BY Timestamp;

SELECT ServiceName, SpanName,
       count() AS n,
       quantile(0.99)(Duration) / 1e6 AS p99_ms
FROM otel_traces
WHERE Timestamp >= now() - INTERVAL 15 MINUTE
GROUP BY ServiceName, SpanName
ORDER BY p99_ms DESC
LIMIT 20;
```

Logs for one service, and the log lines belonging to a single trace — the join
that motivates the `LOGGING_PATTERN_CORRELATION` wiring in
`templates/services.yaml`. Note `SeverityText` is empty: there is no severity
parser, for the reason given in the filelog operators in
`templates/_helpers.tpl`. The level is in the body.

```sql
SELECT Timestamp, SeverityText, Body
FROM otel_logs
WHERE ServiceName = 'journey-order'
  AND Timestamp >= now() - INTERVAL 15 MINUTE
ORDER BY Timestamp DESC
LIMIT 100;

SELECT Timestamp, ServiceName, Body
FROM otel_logs
WHERE TraceId = 'a1b2c3...'
ORDER BY Timestamp;
```

One request across both signals, interleaved — the thing that was not possible
before all three signals shared a store. Pick any correlated log line and follow
its trace across every service that touched it:

```sql
WITH (SELECT TraceId FROM otel_logs
      WHERE Timestamp >= now() - INTERVAL 15 MINUTE AND TraceId != ''
      LIMIT 1) AS tid
SELECT Timestamp, 'span' AS kind, ServiceName, SpanName AS detail
FROM otel_traces WHERE TraceId = tid
UNION ALL
SELECT Timestamp, 'log', ServiceName, substring(Body, 1, 120)
FROM otel_logs WHERE TraceId = tid
ORDER BY Timestamp;
```

Kubernetes events are in `otel_logs` too, tagged so they can be separated from
application logs:

```sql
SELECT Timestamp, Body
FROM otel_logs
WHERE LogAttributes['event.domain'] = 'k8s'
  AND Timestamp >= now() - INTERVAL 1 HOUR
ORDER BY Timestamp DESC;
```

Metrics, by source. `k8s.pod.*` comes from `kubeletstats`, `k8s.deployment.*`
from the `k8s_cluster` receiver, and `container_*` / `kube_*` / `node_*` from the
Prometheus federation:

```sql
SELECT DISTINCT MetricName FROM otel_metrics_gauge ORDER BY MetricName;

SELECT ResourceAttributes['k8s.pod.name'] AS pod,
       avg(Value) AS avg_cpu
FROM otel_metrics_gauge
WHERE MetricName = 'k8s.pod.cpu.utilization'
  AND TimeUnix >= now() - INTERVAL 15 MINUTE
GROUP BY pod
ORDER BY avg_cpu DESC
LIMIT 20;
```

Prometheus itself remains available for ad-hoc PromQL over its shorter local
window:

```bash
kubectl -n train-ticket port-forward svc/prometheus 9090:9090
```

## Retention and Storage

All three signals are **durable**. The store is ClickHouse, writing to a
PersistentVolumeClaim in kind (`data-clickhouse-0`, 50Gi) and to a named Docker
volume in compose (`train-ticket-clickhouse-data`). Both are configured in
`deploy/helm/train-ticket/templates/clickhouse.yaml` and
`platform/observability/docker-compose.yaml`, and both are on by default: a
`helm upgrade --install` of the chart (or `make deploy`) and `make
observability-up` each stand up a persistent store with no extra flags.

### Retention window: 72 hours

`clickhouse.ttl` (default `72h`) is passed to the collector's `clickhouse`
exporter, which writes it as a `TTL` clause on each table it creates. ClickHouse
drops expired parts in the background.

**The TTL is applied at CREATE TABLE time only.** Changing `clickhouse.ttl` on a
cluster whose tables already exist has no effect on those tables — the exporter
issues `CREATE TABLE IF NOT EXISTS`, sees them, and moves on. To change
retention on an existing store, either `ALTER TABLE ... MODIFY TTL` by hand or
drop the database and let the exporter recreate it. This is the one respect in
which retention here is less convenient than the `--badger.span-store-ttl` flag
this replaced, and it is the reason the window is set generously by default.

The window is 72h rather than the 12h the previous trace store used. Columnar
compression pays for it: see the measured figures below.

### Why ClickHouse

The store used to be Jaeger's embedded Badger backend, chosen when only traces
were retained and the argument for it was "no new component". Three things
changed that trade:

- **Metrics and logs are retained now, not just traces.** Badger held spans
  through Jaeger's span-store interface; it has nowhere to put a metric point or
  a log record. Keeping it would have meant adding a metrics store and a log
  store beside it — three components, three retention windows, three query
  languages. ClickHouse holds all three in one place with one TTL.
- **Queries got harder than "fetch this trace id".** The questions worth asking
  cross signals and aggregate: p99 by operation, error rate by service, the log
  lines belonging to one trace. That is SQL, and Jaeger's API does not express
  it.
- **Compression made the disk argument change sides.** Badger measured 1.53 KiB
  on disk per span with this repo's real attribute set. ClickHouse's columnar
  layout on the same telemetry runs roughly an order of magnitude smaller,
  because span attributes repeat heavily across rows and compress accordingly.
  Measured on this cluster: 2.1M spans in **265 MiB**, or ~0.13 KiB/span.

The costs are real and accepted. It is a genuinely new component with its own
image and readiness dependency; the TTL caveat above replaces a first-class
flag; and the single-writer property is unchanged — `replicas` stays 1 and the
PVC is ReadWriteOnce, so there is no horizontal scale here without a
`ClickHouseCluster` and the `cluster_name`/`table_engine` exporter pair that goes
with it (see `train-ticket.clickhouseExporter` in `templates/_helpers.tpl`).

### Capacity arithmetic

The span-rate inputs are measured, not estimated. The two per-span constants
were measured against this repo's actual span shape:

| Measured input | Value | How |
|---|---|---|
| Spans per purchase-chain trace | **5.32** mean (max 14) | 200 live `journey-order`-rooted traces read from the running store |
| Span payload | **1.05 KB** JSON mean | same sample |
| **On-disk cost per span, ClickHouse** | **~0.13 KiB** | 2.1M spans in 265 MiB on this cluster, `system.parts` after merges settled |
| On-disk cost per span, Badger (historical) | 1.53 KiB | the store this replaced, same attribute set |

Span rate, from `docs/09-performance/stress-test-report-2026-07-12.md`:

- Phase 3 records **35 RPS** (10 loadgen pods) up to **220 RPS** (80 pods).
  Those figures count loadgen-issued edge requests.
- Not every edge request roots a 5.32-span trace — polls and browse journeys
  produce single-span traces — so the blended figure used is **3 spans per edge
  request**.
- The default is sized for **35 RPS**, the lowest full-scenario rate in the
  report and a realistic ceiling for one kind node. The 220 RPS peak was
  measured on a *20-node* Volces VKE cluster; `docs/09-performance/baseline-report.md`,
  run on a single-node kind cluster, reached ~1 RPS.

```
  35 RPS x 3 spans/request              =    105 spans/s
 105 spans/s x 0.13 KiB/span            =   13.7 KiB/s   = 0.05 GiB/h
0.05 GiB/h x 72 h retention             =    3.5 GiB      (traces)
```

Traces are the smallest of the three signals by disk. Logs dominate: this
cluster's `notification` service alone produces more log records than any
service produces spans, and log bodies compress less well than span attributes.
Metrics sit between the two. The 50Gi default is sized so that the **TTL**, not
the disk, is the binding limit for all three together at the default rate.

### Scaling for higher rates

| Edge RPS | Spans/s | Trace disk rate | 72h traces | Notes |
|---------:|--------:|----------------:|-----------:|-------|
| 35 (default) | 105 | 0.05 GiB/h | 3.5 GiB | TTL binds at 50Gi |
| 103 | 309 | 0.14 GiB/h | 10.4 GiB | TTL binds at 50Gi |
| 220 (peak) | 660 | 0.31 GiB/h | 22.3 GiB | add headroom for logs; see `values-prod.yaml` (200Gi) |

If the disk rather than the TTL becomes the binding limit, either grow
`clickhouse.storage.size` or shorten `clickhouse.ttl` — but note the TTL caveat
above: shortening it only affects tables created afterwards.

> **kind caveat.** The `standard` StorageClass in kind is
> `rancher.io/local-path`, which is hostPath-backed and does **not** enforce the
> 50Gi request — it creates a directory under `/var/local-path-provisioner/` on
> the node. The figure documents intent and is enforced on any cluster with a
> real provisioner; on kind the TTL is the only hard bound, so a badly-raised
> TTL will consume node disk. Check headroom with
> `docker exec <kind-node> df -h /`.

Check actual usage rather than assuming:

```sql
SELECT table, formatReadableSize(sum(bytes_on_disk)) AS size, sum(rows) AS rows
FROM system.parts WHERE database = 'otel' AND active
GROUP BY table ORDER BY sum(bytes_on_disk) DESC;
```

### Export reliability across a store restart

The store is a stateful component that goes offline for a few seconds when its
pod is replaced, so every collector's `clickhouse` exporter is configured with
`retry_on_failure` (5s to 30s backoff, 300s ceiling) and a bounded in-memory
`sending_queue`. Signals emitted while the store is restarting are redelivered
rather than dropped.

A consequence worth expecting: a row count taken before a store restart and
again after can come back **higher**, not equal, because the queue drains on
reconnect. That is the mechanism working.

The queue is in memory, so this covers a store restart, not a *collector*
restart. Making that lossless too would need the `file_storage` extension and a
PVC per collector; that is deliberately out of scope.

### Verifying persistence end to end

```bash
# 1. note a row count with an explicit cutoff, so later writes cannot mask a loss
CUT=$(kubectl -n train-ticket exec clickhouse-0 -- \
  clickhouse-client --password trainticket-otel --query "SELECT now()")
kubectl -n train-ticket exec clickhouse-0 -- clickhouse-client \
  --password trainticket-otel \
  --query "SELECT count() FROM otel.otel_traces WHERE Timestamp < '$CUT'"

# 2. destroy the store pod
kubectl -n train-ticket delete pod clickhouse-0
kubectl -n train-ticket wait --for=condition=ready pod/clickhouse-0 --timeout=300s

# 3. the same query must return at least the same count
kubectl -n train-ticket exec clickhouse-0 -- clickhouse-client \
  --password trainticket-otel \
  --query "SELECT count() FROM otel.otel_traces WHERE Timestamp < '$CUT'"
```

Deleting the volume is the only way to lose telemetry:

```bash
kubectl -n train-ticket delete pvc data-clickhouse-0   # kind
docker volume rm train-ticket-clickhouse-data          # compose
```

## Service Contract

Every service should use the same baseline environment shape when real
OpenTelemetry SDK instrumentation is enabled. Only trace export is enabled: the
services' metrics and logs reach the store without the SDK — logs by tailing pod
stdout, metrics from the kubelet and Kubernetes API — so
`OTEL_METRICS_EXPORTER=none` and `OTEL_LOGS_EXPORTER=none` are the intended
steady state here, not a gap waiting to be closed. Turning them on would add a
second path for signals already collected. See
[Where logs come from](#where-logs-come-from-and-why-not-the-sdk).

```bash
OTEL_SERVICE_NAME=<service-id>
OTEL_RESOURCE_ATTRIBUTES=service.namespace=train-ticket,deployment.environment=local
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
```

Inside the kind cluster, all 38 business services receive the same standard
OpenTelemetry variables, set once in the shared env block of
`deploy/helm/train-ticket/templates/services.yaml`:

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
- The telemetry-store StatefulSet must stay `replicas: 1`. ClickHouse holds an
  exclusive lock on its data directory and the PVC is ReadWriteOnce; a second
  replica against the same volume crash-loops. Scaling out needs replication
  configured on both sides — see `train-ticket.clickhouseExporter` in
  `templates/_helpers.tpl` for why `cluster_name` and `table_engine` must move
  together.
- The `otel-cluster` Deployment must stay `replicas: 1`. Its receivers report on
  the cluster, not on a node, so N replicas write N copies of the same rows.
- The retention window (`clickhouse.ttl`) and the volume size
  (`clickhouse.storage.size`) must be changed together, using the arithmetic in
  [Capacity arithmetic](#capacity-arithmetic). Remember that the TTL only applies
  to tables created afterwards.
- The three collectors' exporter, `k8sattributes` and namespace-backfill config
  must stay shared through `templates/_helpers.tpl`. Divergence between them is
  invisible at deploy time and surfaces only as missing rows.
- A collector's `checksum/config` must hash its rendered config, not `.Values`.
  Hashing values misses a template-body edit, which updates the ConfigMap without
  restarting the pod.
