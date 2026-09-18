{{/*
Service image path: registry/org/name:tag

Two repository layouts are supported, selected by `global.imageRepo`:

  - unset (default): one repository PER service -- `org/name:tag`.
  - set: one repository for the WHOLE chart, with the service name folded into
    the tag -- `org/repo:<name>-<tag>`. Registries that bill or permission per
    repository (ACR, Harbor) make a per-service layout 39 things to create and
    publish; a single repository is one. It also means a pull secret or a
    public/private toggle only ever has to be applied once.

`name` is the service key, which is also the Deployment name, so the tag reads
as `<service>-<build>`, e.g. `journey-order-20260913`.
*/}}
{{- define "train-ticket.image" -}}
{{- $registry := .global.imageRegistry -}}
{{- $org := .global.imageOrg -}}
{{- $name := .name -}}
{{- $tag := .global.imageTag -}}
{{- if .global.imageRepo -}}
{{- $tag = printf "%s-%s" $name (toString $tag) -}}
{{- $name = .global.imageRepo -}}
{{- end -}}
{{- if $registry -}}
{{ $registry }}/{{ $org }}/{{ $name }}:{{ $tag }}
{{- else -}}
{{ $org }}/{{ $name }}:{{ $tag }}
{{- end -}}
{{- end -}}

{{/*
Infra image path: registry/repo:tag

`global.infraRegistry` overrides `global.imageRegistry` here, and it is read
with hasKey rather than `| default` so that an EXPLICIT empty string means
"docker.io" instead of silently falling back.

That distinction is load-bearing. The chart was written against a pull-through
mirror (pair-cn-guangzhou.cr.volces.com), where prefixing `library/` onto
`postgres` resolves because the mirror proxies docker.io. A plain registry does
not: pointing imageRegistry at ACR renders
`.../library/postgres:16-alpine`, a namespace that does not exist, and every
Postgres, Redis, busybox init container, Jaeger, OTel collector and Mailpit pod
goes ImagePullBackOff while the 39 service images pull fine.

WHEN `global.imageRepo` IS SET, infra images are mirrored into that same single
repository, tagged `<basename>-<tag>` (`postgres-16-alpine`,
`all-in-one-1.57`). That is not decoration: it is what lets a cluster with no
route to docker.io run at all. `admin:school` can reach a registry but times out
against `auth.docker.io`, so `curlimages/curl`, `jaegertracing/all-in-one` and
`axllent/mailpit` all fail to pull -- while `postgres:16-alpine` and
`redis:7-alpine` succeed only because those layers are already cached on the
nodes, which is a property of the nodes' history and not something a deploy can
rely on. Putting the upstream images in the same repository as the services
makes the release depend on exactly one reachable registry.

Setting `infraRegistry: ""` explicitly opts out and sends infra images back to
docker.io, for a cluster that can reach it and does not want them mirrored.
*/}}
{{- define "train-ticket.infraImage" -}}
{{- $registry := .global.imageRegistry -}}
{{- if hasKey .global "infraRegistry" -}}
{{- $registry = .global.infraRegistry -}}
{{- end -}}
{{- $repo := .repo -}}
{{- $tag := .tag -}}
{{- if and $registry .global.imageRepo -}}
{{- $tag = printf "%s-%s" (base $repo) (toString $tag) -}}
{{ $registry }}/{{ .global.imageOrg }}/{{ .global.imageRepo }}:{{ $tag }}
{{- else if $registry -}}
{{- if contains "/" $repo -}}
{{ $registry }}/{{ $repo }}:{{ $tag }}
{{- else -}}
{{ $registry }}/library/{{ $repo }}:{{ $tag }}
{{- end -}}
{{- else -}}
{{ .repo }}:{{ .tag }}
{{- end -}}
{{- end -}}

{{/*
Common labels.

Not currently used: the templates inline the two labels they actually need
(app.kubernetes.io/name and /part-of), because those two are what every
selector in this chart -- and every `kubectl get -l` in the deploy scripts and
e2e suite -- matches on. Kept because adding managed-by/chart labels to a
Deployment is a spec.selector-adjacent change that must be made deliberately,
in one place.

Call with a dict carrying both the root context and the name:
  {{- include "train-ticket.labels" (dict "root" $ "name" $name) }}
*/}}
{{- define "train-ticket.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: train-ticket
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: train-ticket-{{ .root.Chart.Version }}
{{- end -}}

{{/*
The `clickhouse` exporter block, shared by all three collectors.

Defined once because a divergence between them is invisible: each collector
would keep working and write to a different database or table set, and the
mistake only surfaces as a query returning fewer rows than expected. Callers
pass the root context and indent it themselves.

  {{- include "train-ticket.clickhouseExporter" . | nindent 6 }}

Credentials go in the DSN query string rather than the separate `username:` /
`password:` keys. Both forms work; one string keeps the endpoint, timeouts and
auth in a single value that can be copy-pasted into clickhouse-client.

Note what is deliberately ABSENT: `cluster_name` and `table_engine`. Setting
cluster_name alone wraps the DDL in ON CLUSTER while leaving the engine a plain
MergeTree, so replicas would silently keep independent data. Both belong
together, and only on a multi-replica store; this chart runs one ClickHouse pod.
*/}}
{{- define "train-ticket.clickhouseExporter" -}}
clickhouse:
  endpoint: tcp://clickhouse:9000?dial_timeout=10s&compress=lz4&username={{ .Values.clickhouse.auth.username }}&password={{ .Values.clickhouse.auth.password }}
  database: {{ .Values.clickhouse.database | quote }}
  # The exporter owns its schema: it issues CREATE TABLE IF NOT EXISTS on
  # startup for each signal it handles, plus the otel_traces_trace_id_ts
  # materialized view. Nothing else in this chart creates telemetry tables.
  create_schema: true
  # Applied as a TTL clause when the tables are created. Raising it later does
  # not alter tables that already exist -- see templates/clickhouse.yaml.
  ttl: {{ .Values.clickhouse.ttl }}
  logs_table_name: otel_logs
  traces_table_name: otel_traces
  # A PREFIX, not a table: the exporter creates otel_metrics_gauge,
  # _sum, _histogram, _exponential_histogram and _summary from it.
  metrics_table_name: otel_metrics
  timeout: 10s
  # ClickHouse is a restartable stateful component holding an exclusive lock on
  # its volume, so replacing the pod takes it offline for a few seconds. Retry
  # plus a bounded in-memory queue means signals emitted in that window are
  # redelivered rather than dropped. The queue is in memory, so this covers a
  # STORE restart, not a collector restart.
  retry_on_failure:
    enabled: true
    initial_interval: 5s
    max_interval: 30s
    max_elapsed_time: 300s
  sending_queue:
    enabled: true
    num_consumers: {{ .Values.otelCollector.sendingQueue.numConsumers }}
    queue_size: {{ .Values.otelCollector.sendingQueue.queueSize }}
{{- end -}}

{{/*
The `k8sattributes` processor block, shared by all three collectors.

Every signal that reaches ClickHouse must carry k8s.namespace.name, k8s.pod.name
and service.name, because those are the columns any query filters on. This
processor is what puts them there, and it needs pod/namespace/replicaset RBAC to
do it (templates/otel-rbac.yaml) -- without that the attributes are simply
absent and queries filter to zero rows against a table that is filling up
normally.
*/}}
{{- define "train-ticket.k8sattributes" -}}
k8sattributes:
  auth_type: serviceAccount
  passthrough: false
  extract:
    metadata:
      - k8s.namespace.name
      - k8s.pod.name
      - k8s.pod.uid
      - k8s.pod.start_time
      - k8s.deployment.name
      - k8s.statefulset.name
      - k8s.daemonset.name
      - k8s.node.name
      - k8s.container.name
      - container.image.name
      - container.image.tag
    labels:
      # Every workload in this chart is labelled app.kubernetes.io/name (see
      # templates/services.yaml), which is also the value each service passes as
      # OTEL_SERVICE_NAME. Extracting it here is what populates ServiceName for
      # signals the app SDK does not originate -- pod logs and kubelet metrics.
      - tag_name: service.name
        key: app.kubernetes.io/name
        from: pod
      - tag_name: app.label.part_of
        key: app.kubernetes.io/part-of
        from: pod
  pod_association:
    - sources:
        - from: resource_attribute
          name: k8s.pod.ip
    - sources:
        - from: resource_attribute
          name: k8s.pod.uid
    # Load-bearing for pod logs. filelog derives only a pod name and namespace
    # from the log file path -- no IP, no UID -- so without this rule none of
    # the rules above match and every log line arrives unenriched, with a blank
    # ServiceName. Rules are evaluated in order and the first whose sources are
    # all present wins, so this sits after the cheaper identity matches.
    - sources:
        - from: resource_attribute
          name: k8s.pod.name
        - from: resource_attribute
          name: k8s.namespace.name
    - sources:
        - from: connection
{{- end -}}

{{/*
Backfill service.namespace from k8s.namespace.name, for all three signals.

Signals arrive from SDKs that set service.namespace (the instrumented services),
from receivers that do not (kubeletstats, k8s_cluster, prometheus), and from
filelog. Deriving it here, after k8sattributes has run, means a query can filter
on service.namespace uniformly instead of special-casing the source.
*/}}
{{- define "train-ticket.serviceNamespaceProcessor" -}}
resource/service_namespace:
  attributes:
    - key: service.namespace
      from_attribute: k8s.namespace.name
      action: insert
{{- end -}}
