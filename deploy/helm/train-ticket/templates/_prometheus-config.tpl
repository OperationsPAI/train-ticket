{{/*
The scrape configuration, as a named template so the Deployment can hash the
rendered result. Hashing only .Values.prometheus meant a change to this file --
a new scrape limit, a new label the allowlist keeps -- produced an identical
checksum, so the pod was never rolled and Prometheus kept running the old
configuration while the ConfigMap beside it held the new one.
*/}}
{{- define "train-ticket.prometheusConfig" -}}
global:
  scrape_interval: {{ .Values.prometheus.scrapeInterval }}
  evaluation_interval: {{ .Values.prometheus.scrapeInterval }}
  external_labels:
    cluster: {{ .Values.prometheus.clusterLabel | quote }}

rule_files:
  - /etc/prometheus/rules.yaml

scrape_configs:
  - job_name: prometheus
    static_configs:
      - targets: ['localhost:9090']

  # cAdvisor, via the kubelet's read-only API on the node. This is the job
  # the whole Prometheus tier exists for: container_cpu_*, container_memory_*
  # and container_network_* have no OTel receiver.
  #
  # Needs nodes/proxy in the collector-adjacent RBAC (this pod's own
  # ServiceAccount, below) and insecure_skip_verify because the kubelet's
  # serving certificate is self-signed rather than issued by the cluster CA.
  - job_name: kubelet-cadvisor
    scheme: https
    metrics_path: /metrics/cadvisor
    honor_labels: true
    honor_timestamps: true
    authorization:
      type: Bearer
      credentials_file: /var/run/secrets/kubernetes.io/serviceaccount/token
    tls_config:
      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      insecure_skip_verify: true
    kubernetes_sd_configs:
      - role: node
    relabel_configs:
      # The node role gives an address of <node>:10250 and the node's own
      # labels; the apiserver proxy path is how a pod reaches it without
      # trusting the kubelet's certificate chain.
      - action: labelmap
        regex: __meta_kubernetes_node_label_(.+)
      - source_labels: [__meta_kubernetes_node_name]
        action: replace
        target_label: node
    metric_relabel_configs:
      # cAdvisor emits a series per cgroup, most of which are not pods. An
      # empty pod label means a system slice.
      - source_labels: [pod]
        action: drop
        regex: ^$
      # High-cardinality series with no diagnostic value at this scrape
      # interval; they roughly double the sample count on their own.
      - source_labels: [__name__]
        action: drop
        regex: container_(cpu_(load_average_10s|system_seconds_total|user_seconds_total)|fs_(io_current|reads_merged_total|sector_reads_total|sector_writes_total|writes_merged_total)|memory_(mapped_file|swap)|file_descriptors|tasks_state|threads_max|spec_.*)

  - job_name: kubelet
    scheme: https
    metrics_path: /metrics
    honor_labels: true
    authorization:
      type: Bearer
      credentials_file: /var/run/secrets/kubernetes.io/serviceaccount/token
    tls_config:
      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      insecure_skip_verify: true
    kubernetes_sd_configs:
      - role: node
    relabel_configs:
      - action: labelmap
        regex: __meta_kubernetes_node_label_(.+)
      - source_labels: [__meta_kubernetes_node_name]
        action: replace
        target_label: node

  - job_name: kubernetes-apiservers
    scheme: https
    authorization:
      type: Bearer
      credentials_file: /var/run/secrets/kubernetes.io/serviceaccount/token
    tls_config:
      ca_file: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
      insecure_skip_verify: true
    kubernetes_sd_configs:
      - role: endpoints
    relabel_configs:
      - source_labels: [__meta_kubernetes_namespace, __meta_kubernetes_service_name, __meta_kubernetes_endpoint_port_name]
        action: keep
        regex: default;kubernetes;https

  {{- if .Values.kubeStateMetrics.enabled }}
  - job_name: kube-state-metrics
    static_configs:
      - targets: ['kube-state-metrics:8080']
  {{- end }}

  {{- if .Values.nodeExporter.enabled }}
  # Discovered rather than hardcoded, so this keeps working as nodes are
  # added or replaced. The DaemonSet is hostNetwork, so the node address and
  # the exporter's port are what to scrape.
  - job_name: node-exporter
    kubernetes_sd_configs:
      - role: node
    relabel_configs:
      - source_labels: [__address__]
        action: replace
        regex: ([^:]+)(?::\d+)?
        replacement: $1:{{ .Values.nodeExporter.port }}
        target_label: __address__
      - action: labelmap
        regex: __meta_kubernetes_node_label_(.+)
      - source_labels: [__meta_kubernetes_node_name]
        action: replace
        target_label: node
  {{- end }}

  {{- if and .Values.otelCollector.enabled .Values.otelCollector.prometheusExporter.enabled }}
  # Business services publish OTLP metrics to the gateway. They do not share
  # a native /metrics endpoint, so scrape the gateway's bounded Prometheus
  # exporter once instead of creating one failing target per application pod.
  - job_name: train-ticket-applications
    scrape_interval: {{ .Values.prometheus.applicationMetrics.scrapeInterval }}
    # Reject a malformed or unexpectedly high-cardinality OTLP export before
    # it can consume unbounded Prometheus memory. The current aggregate is
    # below 4k samples with at most 10 labels per sample, leaving substantial
    # headroom for the remaining business metric contracts.
    sample_limit: 20000
    label_limit: 40
    label_name_length_limit: 128
    label_value_length_limit: 256
    static_configs:
      - targets: ['otel-collector:{{ .Values.otelCollector.prometheusExporter.port }}']
    metric_relabel_configs:
      # Defence in depth after the Collector's OTTL allowlist. Keep only the
      # bounded labels that recording rules and failure exercises consume.
      - action: labelkeep
        regex: __name__|job|instance|exported_job|exported_instance|service|namespace|http_route|http_request_method|http_response_status_code|network_protocol_version|url_scheme|db_client_connection_pool_name|db_client_connection_state|pool|jvm_memory_pool_name|jvm_memory_type|jvm_gc_name|jvm_gc_action|jvm_gc_cause|go_memory_type|go_gc_type|v8js_heap_space_name|v8js_gc_type|nodejs_eventloop_state|workflow|stage|state|action|outcome|reason_code|currency|channel|operation|travel_class|seat_mode|ticket_type|queue|event_stream|consumer_group|event_type|kind|scheduled_job|signal_type|source|source_service|target_service|le|quantile
  {{- end }}

  # Opt-in scraping for application pods, same annotation contract as the
  # cluster collector's own prometheus receiver.
  - job_name: kubernetes-pods
    kubernetes_sd_configs:
      - role: pod
    relabel_configs:
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_scrape]
        action: keep
        regex: true
      - source_labels: [__meta_kubernetes_pod_annotation_prometheus_io_path]
        action: replace
        target_label: __metrics_path__
        regex: (.+)
      - source_labels: [__address__, __meta_kubernetes_pod_annotation_prometheus_io_port]
        action: replace
        regex: ([^:]+)(?::\d+)?;(\d+)
        replacement: $1:$2
        target_label: __address__
      - source_labels: [__meta_kubernetes_namespace]
        action: replace
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        action: replace
        target_label: pod
      - source_labels: [__meta_kubernetes_pod_phase]
        action: drop
        regex: Pending|Succeeded|Failed|Completed
{{- end -}}
