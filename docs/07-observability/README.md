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

## Runtime Seams

Language runtime baselines keep OpenTelemetry optional by default:

- Go services use `platform/go-runtime` middleware for request and correlation
  identifiers plus an observer hook.
- Python FastAPI services expose a `tracer` hook on `create_app`.
- TypeScript Fastify services expose `createApp({ onRequest, startSpan })`.
- Java Spring services expose `RuntimeTracer` with a default no-op bean.
- Rust Axum-compatible modules expose the shared-kernel observer seam.

Tests use the no-op/default path or in-memory recording adapters. Real
OpenTelemetry SDK adapters should bind to these seams and export OTLP to the
collector using the environment contract above.

## Rules

- No service test may require a running collector.
- Request and correlation IDs must be included in emitted trace attributes.
- `/metadata` should disclose that tracing is opt-in and no-op by default until
  a runtime adapter is installed.
- Collector exporter changes must keep OTLP HTTP and gRPC receiver ports stable
  unless all service deployment templates are updated in the same change.
