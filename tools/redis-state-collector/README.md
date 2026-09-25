# Redis state collector

One process that reads the Redis Streams transport state for every Train Ticket
event stream and publishes it to the OTLP gateway as low-cardinality business
metrics.

It exists as a single reporter because the quantities it publishes are absolute
counts rather than per-process observations. A subscriber that reported its own
lag would publish the same stream once per replica, and could not report on a
stream its own service does not consume; the transport state belongs to Redis,
so it is read once and queried from the metric.

## Exported metrics

The names are the contract in
[`docs/07-observability/business-metrics.md`](../../docs/07-observability/business-metrics.md).
The collector appends a unit suffix, so `train_ticket.event.consumer.oldest_age`
with unit `s` becomes `train_ticket_event_consumer_oldest_age_seconds` in
Prometheus.

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `train_ticket.event.consumer.lag` | Gauge | `event_stream`, `consumer_group` | Entries behind the group's delivered position (`XINFO GROUPS` `lag`). |
| `train_ticket.event.consumer.pending` | Gauge | `event_stream`, `consumer_group` | Entries delivered to the group and not acknowledged. |
| `train_ticket.event.consumer.oldest_age` | Gauge, seconds | `event_stream`, `consumer_group` | Age of the oldest entry the group still owes. |
| `train_ticket.event.dlq.entries` | Gauge | `event_stream`, `consumer_group`, `event_type` | Current dead-letter population. |

Label values come from the transport itself: stream names, consumer group
names, and the event type of a dead-letter envelope. Anything unreadable
collapses to the literal `unknown`, so a malformed payload can never become a
label of its own. No message id, order id, user id, request id, correlation id,
hostname, URL or exception text is ever attached.

## Environment

| Variable | Default | Purpose |
| --- | --- | --- |
| `REDIS_URL` | `redis://localhost:6379` | Redis endpoint holding the `events:*` streams. |
| `PORT` | `8080` | Port for `/healthz` and `/readyz`. |
| `TRANSPORT_POLL_INTERVAL_SECONDS` | `15` | How often the snapshot is refreshed. |
| `OTEL_SERVICE_NAME` | `redis-state-collector` | Resource service name on every exported series. |
| `OTEL_METRICS_EXPORTER` | unset | Any non-empty value other than `none` enables export. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | unset | OTLP gRPC endpoint. Without it the poller runs and exports nothing. |

## Cost

One poll is a `SCAN` of the `events:*` namespace, one `XINFO GROUPS` per stream,
one `XPENDING` summary and at most one `XRANGE ... COUNT 1` per group, and one
`XLEN` plus a bounded `XRANGE` per dead-letter stream. It never reads a whole
stream with `XRANGE` and never calls `XAUTOCLAIM`.

A poll that cannot read Redis leaves the previous snapshot in place and reports
not-ready, so "the collector is blind" and "the transport is idle" do not
produce the same series.

The chart runs exactly one replica; see
[`deploy/helm/train-ticket/templates/transport-collector.yaml`](../../deploy/helm/train-ticket/templates/transport-collector.yaml).

## Tests

```bash
go test ./...
```

The unit tests cover stream-name classification, entry-id timestamps, label
collapse, and the exported label sets. The end-to-end test uses `miniredis` to
prove the lag, pending, oldest-age and dead-letter reads against real Redis
commands.
