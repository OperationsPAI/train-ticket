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

The debug exporters are intentional for the greenfield baseline. They make local
signal flow visible without requiring Jaeger, Prometheus, Loki, ClickHouse, or a
vendor backend. Production deployment can replace or extend the exporters while
preserving the same service-side OTLP contract.

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

The compose file lives at `platform/observability/docker-compose.yaml`.

## Service Contract

Every service should use the same baseline environment shape when real
OpenTelemetry SDK instrumentation is enabled:

```bash
OTEL_SERVICE_NAME=<service-id>
OTEL_RESOURCE_ATTRIBUTES=service.namespace=train-ticket,deployment.environment=local
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
```

Inside a compose network, use `http://otel-collector:4318` for
`OTEL_EXPORTER_OTLP_ENDPOINT`.

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
