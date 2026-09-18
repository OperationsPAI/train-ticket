{{/*
Collector configs, one named template each.

They live here rather than inline in their workload templates because each is
consumed TWICE -- once as the ConfigMap body and once by the Deployment's or
DaemonSet's checksum/config annotation -- and both must see identical bytes. See
the note on train-ticket.gatewayConfig below.

Blocks shared between all three (the clickhouse exporter, k8sattributes, the
service.namespace backfill) are in _helpers.tpl.
*/}}
{{- /*
The gateway config is a named template rather than inline YAML so the ConfigMap
below and the Deployment's checksum/config annotation consume the SAME rendered
bytes. Hashing .Values instead was tried and is subtly wrong: it misses a change
to the template body, so editing this config and running `helm upgrade` updated
the ConfigMap while leaving the running collector on the old config until
someone restarted it by hand. That cost a debugging cycle -- the config was
visibly correct in the ConfigMap and visibly not in effect in the pod.
*/}}
{{- define "train-ticket.gatewayConfig" -}}
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    check_interval: 5s
    # Must stay above the sending_queue below: a limiter smaller than the
    # queue makes the collector refuse spans it could buffer. Keep below
    # otelCollector.resources.limits.memory.
    limit_mib: {{ .Values.otelCollector.memoryLimitMiB }}
  {{- include "train-ticket.k8sattributes" . | nindent 2 }}
  {{- include "train-ticket.serviceNamespaceProcessor" . | nindent 2 }}
  batch:
    timeout: 2s
    send_batch_size: 1024

exporters:
  {{- include "train-ticket.clickhouseExporter" . | nindent 2 }}

extensions:
  health_check:
    endpoint: 0.0.0.0:13133
  zpages:
    endpoint: 0.0.0.0:55679

service:
  telemetry:
    logs:
      level: info
    metrics:
      # Must be set explicitly. This collector version defaults the telemetry
      # endpoint to localhost:8888, which is only reachable from inside the pod
      # -- so the Service's metrics port answers nothing and
      # deploy/e2e/13-observability.sh cannot read
      # otelcol_receiver_accepted_spans to prove signals are flowing.
      readers:
        - pull:
            exporter:
              prometheus:
                host: 0.0.0.0
                port: 8888
  extensions:
    - health_check
    - zpages
  {{- /*
  Processor order is load-bearing and identical in all three collectors:
  memory_limiter first so load is shed before any work is done on it,
  k8sattributes before the namespace backfill that reads its output, and
  batch last so batches are formed from finished records.
  */}}
  pipelines:
    traces:
      receivers:
        - otlp
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - batch
      exporters:
        - clickhouse
    metrics:
      receivers:
        - otlp
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - batch
      exporters:
        - clickhouse
    logs:
      receivers:
        - otlp
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - batch
      exporters:
        - clickhouse
{{- end -}}

{{/*
The otel-agent collector config. A named template so the ConfigMap and the
checksum/config annotation consume the same rendered bytes -- see the note on
train-ticket.gatewayConfig.
*/}}
{{- define "train-ticket.agentConfig" -}}
receivers:
  filelog:
    include:
      - /var/log/pods/*/*/*.log
    exclude:
      # Its own logs, and the other collectors': the collector logs every
      # export it performs, so ingesting that produces a feedback loop that
      # grows without bound.
      - /var/log/pods/*_otel-agent-*_*/*/*.log
      - /var/log/pods/*_otel-collector-*_*/*/*.log
      - /var/log/pods/*_otel-cluster-*_*/*/*.log
      {{- range .Values.otelCollector.agent.filelog.excludeNamespaces }}
      - /var/log/pods/{{ . }}_*/*/*.log
      {{- end }}
    # NOT `beginning`. The kubelet keeps rotated logs on disk, so on every
    # restart `beginning` replays hours or days of history -- and because the
    # exporter queue is FIFO, live records queue behind the backlog and a
    # query for the last five minutes returns nothing while the collector
    # works through the past. `end` costs the records written during a
    # restart and keeps the store current, which is the right trade for a
    # store being queried in near-real-time.
    start_at: end
    include_file_path: true
    include_file_name: false
    operators:
      # The container runtime's log format is not knowable from config --
      # containerd and CRI-O write a text prefix, Docker writes JSON -- so
      # route on the shape of the line. This node runs containerd, but the
      # other two branches cost nothing and make the chart portable.
      - type: router
        id: get-format
        routes:
          - output: parser-docker
            expr: 'body matches "^\\{"'
          - output: parser-crio
            expr: 'body matches "^[^ Z]+ "'
          - output: parser-containerd
            expr: 'body matches "^[^ Z]+Z"'
      - type: regex_parser
        id: parser-crio
        regex: '^(?P<time>[^ Z]+) (?P<stream>stdout|stderr) (?P<logtag>[^ ]*) ?(?P<log>.*)$'
        output: extract_metadata_from_filepath
        timestamp:
          parse_from: attributes.time
          layout_type: gotime
          layout: '2006-01-02T15:04:05.999999999Z07:00'
      - type: regex_parser
        id: parser-containerd
        regex: '^(?P<time>[^ ^Z]+Z) (?P<stream>stdout|stderr) (?P<logtag>[^ ]*) ?(?P<log>.*)$'
        output: extract_metadata_from_filepath
        timestamp:
          parse_from: attributes.time
          layout: '%Y-%m-%dT%H:%M:%S.%LZ'
      - type: json_parser
        id: parser-docker
        output: extract_metadata_from_filepath
        timestamp:
          parse_from: attributes.time
          layout: '%Y-%m-%dT%H:%M:%S.%LZ'
      # The kubelet encodes the identity of the pod in the path, and it is
      # the only place this receiver can learn it from.
      - type: regex_parser
        id: extract_metadata_from_filepath
        # Pod UIDs are not always 36 characters (static pods use a shorter
        # hash), so the length is a range.
        regex: '^.*\/(?P<namespace>[^_]+)_(?P<pod_name>[^_]+)_(?P<uid>[a-f0-9\-]{16,36})\/(?P<container_name>[^\._]+)\/(?P<restart_count>\d+)\.log$'
        parse_from: attributes["log.file.path"]
        cache:
          size: 128  # the kubelet's default max pods per node is 110
      # Trace correlation. This is what joins a log line to its span in
      # otel_traces, and it is why templates/services.yaml sets
      # LOGGING_PATTERN_CORRELATION="[trace=%X{trace_id:-} span=%X{span_id:-}] ":
      # the services log PLAIN TEXT with the ids in that bracketed prefix, not
      # JSON. Parsing them out of the prefix is therefore the only way to
      # populate TraceId/SpanId on a log record.
      #
      # `preserve_to` keeps the full line as the body -- without it the regex
      # replaces the body with just the captured groups and the message is lost.
      # The `if` guard matters: the Python services, the init containers and
      # Spring's own startup banner have no such prefix, and a regex_parser
      # DROPS every record it cannot match.
      #
      # The MDC renders `trace=` with an empty value when there is no active
      # span, so the pattern requires at least one hex digit rather than
      # matching greedily -- an empty TraceId column is worse than an absent one,
      # because it looks like a correlation that failed rather than a log line
      # emitted outside a trace.
      #
      # Deliberately no severity parser: the services do not agree on a field
      # name for level, and a severity parser hard-fails -- and discards -- any
      # record missing the field it was pointed at.
      - type: regex_parser
        id: extract_trace_context
        parse_from: attributes.log
        preserve_to: attributes.log
        if: 'attributes.log matches "\\[trace=[0-9a-f]+ span=[0-9a-f]+\\]"'
        regex: '\[trace=(?P<trace_id>[0-9a-f]+) span=(?P<span_id>[0-9a-f]+)\]'
        trace:
          trace_id:
            parse_from: attributes.trace_id
          span_id:
            parse_from: attributes.span_id
      - type: move
        from: attributes["log.file.path"]
        to: resource["log.file.path"]
      - type: move
        from: attributes.container_name
        to: resource["k8s.container.name"]
      - type: move
        from: attributes.namespace
        to: resource["k8s.namespace.name"]
      - type: move
        from: attributes.pod_name
        to: resource["k8s.pod.name"]
      # k8s.pod.name + k8s.namespace.name above are exactly what the third
      # pod_association rule in the k8sattributes processor matches on.
      # Without them set as RESOURCE attributes (not log attributes) that
      # rule cannot fire and ServiceName stays blank.
      - type: move
        from: attributes.log
        to: body

  kubeletstats:
    collection_interval: {{ .Values.otelCollector.agent.kubeletstats.collectionInterval }}
    auth_type: serviceAccount
    # The node NAME, not its IP: with hostNetwork off (see the DaemonSet
    # below) the pod reaches the kubelet through cluster DNS.
    endpoint: https://${env:K8S_NODE_NAME}:10250
    node: ${env:K8S_NODE_NAME}
    # The kubelet's serving certificate is self-signed and not issued by the
    # cluster CA, so verification cannot succeed here.
    insecure_skip_verify: true
    k8s_api_config:
      # Required for the pod and container groups: the receiver has to ask
      # the API server to resolve the pods the kubelet reports on.
      auth_type: serviceAccount
    metric_groups:
      - node
      - pod
      - container
    metrics:
      k8s.pod.cpu.node.utilization:
        enabled: true
      k8s.pod.memory.node.utilization:
        enabled: true
      k8s.pod.cpu_limit_utilization:
        enabled: true
      k8s.pod.memory_limit_utilization:
        enabled: true

  hostmetrics:
    collection_interval: {{ .Values.otelCollector.agent.hostmetrics.collectionInterval }}
    # The container's own / is the image, not the node. Every scraper reads
    # through this prefix, which is why /hostfs is mounted below.
    root_path: /hostfs
    scrapers:
      cpu: {}
      load: {}
      memory: {}
      disk: {}
      network: {}
      filesystem:
        exclude_mount_points:
          # Pseudo-filesystems and the container-runtime overlays. Left in,
          # they produce one series per container layer and swamp the real
          # node filesystems.
          mount_points:
            - /dev/*
            - /proc/*
            - /sys/*
            - /var/lib/docker/*
            - /var/lib/kubelet/*
            - /var/lib/containerd/*
            - /snap/*
          match_type: regexp
        exclude_fs_types:
          fs_types:
            - autofs
            - binfmt_misc
            - bpf
            - cgroup2
            - configfs
            - debugfs
            - devpts
            - devtmpfs
            - fusectl
            - hugetlbfs
            - iso9660
            - mqueue
            - nsfs
            - overlay
            - proc
            - procfs
            - pstore
            - rpc_pipefs
            - securityfs
            - selinuxfs
            - squashfs
            - sysfs
            - tracefs
          match_type: strict

processors:
  memory_limiter:
    # 1s rather than the gateway's 5s: filelog can go from idle to a burst
    # within one interval when a service starts logging a stack trace per
    # request, so this needs to notice sooner.
    check_interval: 1s
    limit_percentage: 80
    spike_limit_percentage: 25
  {{- include "train-ticket.k8sattributes" . | nindent 2 }}
  {{- include "train-ticket.serviceNamespaceProcessor" . | nindent 2 }}
  resourcedetection/node:
    # Stamps k8s.node.name onto host metrics, which otherwise carry no
    # indication of which node produced them.
    detectors: [env, system]
    system:
      hostname_sources: [os]
    override: false
  batch:
    timeout: 5s
    send_batch_size: 2000
    send_batch_max_size: 4000

exporters:
  {{- include "train-ticket.clickhouseExporter" . | nindent 2 }}

extensions:
  health_check:
    endpoint: 0.0.0.0:13133

service:
  telemetry:
    logs:
      level: info
    metrics:
      # Explicit: this collector version defaults to localhost:8888, which
      # nothing outside the pod can scrape. See the note in
      # templates/observability.yaml.
      readers:
        - pull:
            exporter:
              prometheus:
                host: 0.0.0.0
                port: 8888
  extensions:
    - health_check
  pipelines:
    logs:
      receivers:
        - filelog
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - batch
      exporters:
        - clickhouse
    metrics:
      receivers:
        - kubeletstats
        - hostmetrics
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - resourcedetection/node
        - batch
      exporters:
        - clickhouse
{{- end -}}

{{/*
The otel-cluster collector config. A named template so the ConfigMap and the
checksum/config annotation consume the same rendered bytes -- see the note on
train-ticket.gatewayConfig.
*/}}
{{- define "train-ticket.clusterConfig" -}}
receivers:
  k8s_cluster:
    collection_interval: {{ .Values.otelCollector.cluster.k8sCluster.collectionInterval }}
    node_conditions_to_report:
      - Ready
      - MemoryPressure
      - DiskPressure
      - PIDPressure
    allocatable_types_to_report:
      - cpu
      - memory
      - pods

  k8sobjects:
    objects:
      - name: events
        mode: watch
        group: events.k8s.io
        # A deletion is the event object aging out of etcd, not something
        # happening in the cluster; recording it would put rows in otel_logs
        # that describe the observer rather than the workload.
        exclude_watch_type:
          - DELETED

  prometheus:
    config:
      scrape_configs:
        {{- if .Values.prometheus.enabled }}
        # Prometheus's aggregate, pulled through /federate. honor_labels is
        # required: these samples already carry job/instance from the
        # original scrape, and without it the receiver would overwrite them
        # with this job's own.
        - job_name: prometheus-federate
          scrape_interval: {{ .Values.otelCollector.cluster.federate.scrapeInterval }}
          honor_labels: true
          metrics_path: /federate
          params:
            'match[]':
              {{- range .Values.otelCollector.cluster.federate.match }}
              - {{ . | quote }}
              {{- end }}
          static_configs:
            - targets:
                - prometheus:9090
        {{- end }}
        # Anything that opts in with the standard annotations, with no change
        # to this file. Nothing in this chart sets them today; it is the
        # contract for a service that later exposes /metrics.
        - job_name: kubernetes-pods
          scrape_interval: 30s
          kubernetes_sd_configs:
            - role: pod
          relabel_configs:
            - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
              action: keep
              regex: true
            - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scheme]
              action: replace
              regex: (https?)
              target_label: __scheme__
            - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
              action: replace
              target_label: __metrics_path__
              regex: (.+)
            # NOTE: the collector expands $VAR in its own config, so a
            # literal dollar in a replacement must be doubled. A single $1
            # here silently yields an empty address.
            - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
              action: replace
              regex: ([^:]+)(?::\d+)?;(\d+)
              replacement: $$1:$$2
              target_label: __address__
            - source_labels: [__meta_kubernetes_namespace]
              action: replace
              target_label: namespace
            - source_labels: [__meta_kubernetes_pod_name]
              action: replace
              target_label: pod
            # A Pending or Completed pod has no reachable endpoint; keeping
            # it produces a scrape error every interval.
            - source_labels: [__meta_kubernetes_pod_phase]
              action: drop
              regex: Pending|Succeeded|Failed|Completed

processors:
  memory_limiter:
    check_interval: 5s
    limit_percentage: 80
    spike_limit_percentage: 25
  {{- include "train-ticket.k8sattributes" . | nindent 2 }}
  {{- include "train-ticket.serviceNamespaceProcessor" . | nindent 2 }}
  batch:
    timeout: 5s
    send_batch_size: 2000
    send_batch_max_size: 4000

exporters:
  {{- include "train-ticket.clickhouseExporter" . | nindent 2 }}

extensions:
  health_check:
    endpoint: 0.0.0.0:13133

service:
  telemetry:
    logs:
      level: info
    metrics:
      # Explicit: this collector version defaults to localhost:8888, which
      # nothing outside the pod can scrape. See the note in
      # templates/observability.yaml.
      readers:
        - pull:
            exporter:
              prometheus:
                host: 0.0.0.0
                port: 8888
  extensions:
    - health_check
  pipelines:
    metrics:
      receivers:
        - k8s_cluster
        - prometheus
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - batch
      exporters:
        - clickhouse
    logs:
      receivers:
        - k8sobjects
      processors:
        - memory_limiter
        - k8sattributes
        - resource/service_namespace
        - batch
      exporters:
        - clickhouse
{{- end -}}
