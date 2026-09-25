# Platform metrics API audit and first low-cardinality signals

Updated: 2026-09-25, Asia/Beijing.

This document records the metric capabilities that exist in the shared language
kits and the first transport-level business signals implemented for the Java
core path. It describes application instrumentation only. Deployment, collector,
Prometheus, Reporting, Notification and Risk Compliance configuration are out of
scope here.

## Shared platform audit

| Runtime | HTTP and runtime metrics | Database pool metrics | Shared event/outbox metrics before this change | Important boundary |
| --- | --- | --- | --- | --- |
| Python | FastAPI HTTP metrics plus process, thread and CPython GC metrics | connection count/max, pending requests, acquisition errors used as timeout count, cumulative wait time | none | `psycopg_pool.get_stats()` exposes current waiters but only cumulative wait milliseconds, so no wait percentile is claimed |
| TypeScript | Node HTTP/Fastify plus event-loop, heap and GC metrics | connection count/max and pending requests | none | `pg.Pool` exposes `waitingCount` but no reliable acquisition timeout counter or wait-duration hook |
| Go | Gin HTTP duration and active requests | connection count/max, exact pending acquisitions, timeout count and wait-time histogram through pgx acquire hooks | none | services must create the pool through `NewPoolWithMetrics` and register the returned handle |
| Rust | HTTP duration and active requests | connection count/max, timeout count and wait-time histogram | none | sqlx exposes occupancy but not the current waiter queue; the kit does not invent a pending-request gauge |
| Java | HTTP server/client, JVM and HikariCP metrics including pending callers, acquisition timing and timeouts | HikariCP native Micrometer instruments | none | Java has one shared Redis subscriber and outbox relay used by Booking Orchestration, Journey Order and Payment, making it the smallest reliable first implementation surface |

The audit deliberately distinguishes an absent signal from one that can be
derived honestly. In particular, a mean derived from cumulative wait time is not
a latency distribution, and Redis redelivery count is not consumer lag.

## Implemented event-consumer metrics

`RedisEventSubscriber` now records the following OpenTelemetry instruments for
Java services:

| Instrument | Type | Attribute | Allowed values | Meaning |
| --- | --- | --- | --- | --- |
| `train_ticket.event.consumer.messages` | Counter | `outcome` | `success`, `duplicate`, `retry`, `dead_letter` | Final or intermediate disposition of a delivered message |
| `train_ticket.event.consumer.failures` | Counter | `failure.type` | `handler_exception`, `transient_result`, `fatal_result`, `missing_envelope`, `invalid_envelope`, `redelivery_exhausted`, `dlq_write` | A bounded classification of the failure observed by the subscriber |
| `train_ticket.event.consumer.dead_letters` | Counter | `reason` | the same bounded failure classes except normal success outcomes | Messages successfully written to the Redis DLQ |

Semantics:

- `retry` increments for every genuine handler failure that leaves the message
  pending below the dead-letter threshold.
- `dead_letter` and `dead_letters` increment only after the Redis DLQ write
  succeeds. A failed DLQ write increments `failure.type=dlq_write` instead.
- `duplicate` means the consumer group's durable deduplication store already
  contains the event and the Redis entry was acknowledged without executing the
  handler again.
- Failure counters count observations, not distinct messages. A message that
  fails four times before succeeding contributes four failures, four retries and
  one success.

No stream, consumer group, consumer instance, event type, event ID, correlation
ID or business ID is attached. Service identity comes from the OpenTelemetry
resource. This keeps the series budget fixed even when integrations and load-test
entities grow.

## Implemented outbox metrics

`OutboxRelay` now records:

| Instrument | Type | Attribute | Allowed values | Meaning |
| --- | --- | --- | --- | --- |
| `train_ticket.outbox.unpublished.sample` | Gauge | none | n/a | Number of rows returned by the existing head query, bounded to `0..100` |
| `train_ticket.outbox.oldest_unpublished.age` | Gauge, seconds | none | n/a | Age of the oldest row in that ordered head sample |
| `train_ticket.outbox.published` | Counter | none | n/a | Rows included in a Redis pipeline that completed successfully |
| `train_ticket.outbox.failures` | Counter | `stage` | `select`, `publish`, `mark_published` | Relay failures at the database read, Redis publish or database acknowledgement stage |

The backlog gauge is intentionally a bounded sample rather than an exact table
count. The relay reuses its indexed `ORDER BY seq LIMIT 100` query and performs no
additional `count(*)`. A stable value of 100 means at least one full batch is
waiting; the age gauge shows whether that condition is clearing or worsening.
This remains cheap when the outbox contains millions of rows.

`train_ticket.outbox.published` is a transport-attempt counter, not a count of
unique business facts. If Redis accepts a batch and the subsequent
`mark_published` update fails, the batch can be published again. The combination
of a rising published counter and `stage=mark_published` failures is therefore a
direct signal of duplicate-delivery risk.

## Redis lag boundary

Redis consumer lag, pending-entry age and current DLQ population are not emitted
by this first batch. The Java subscriber interface has per-message delivery
count and read/claim/ack operations, but no authoritative `XINFO GROUPS` snapshot.
Redelivery count cannot be substituted: it measures claims for one pending
message and rises with elapsed pending time, not with entries behind the group.

Adding `XINFO` polling independently to every service replica would duplicate
Redis load and publish conflicting gauges during rollout. These state metrics
should be implemented once in a shared collector that owns a configured list of
stream/group pairs. The process counters implemented here remain useful beside
that future state collector: they answer whether handlers are succeeding,
retrying or dead-lettering, while the collector answers how much work remains.

## Fault-exercise combinations

The following combinations create useful diagnosis problems without identifier
labels:

- `unpublished.sample=100` and rising oldest age, with no `publish` failures:
  producer throughput exceeds relay throughput or the database query/update path
  is slow.
- Rising `outbox.failures{stage="publish"}`: Redis publication is unavailable;
  business writes may still succeed while events remain unpublished.
- Rising `outbox.failures{stage="mark_published"}` while `outbox.published`
  increases: Redis accepted messages but PostgreSQL acknowledgement failed, so
  at-least-once redelivery and downstream deduplication are under pressure.
- Rising `event.consumer.messages{outcome="retry"}` together with database pool
  pending/wait metrics: consumer handlers are blocked on PostgreSQL rather than
  falling behind solely because of Redis.
- Rising `failure.type="invalid_envelope"` followed by dead letters: contract or
  serializer drift.
- Rising `failure.type="dlq_write"` without matching dead-letter increments:
  poison messages cannot be durably handed off and remain pending.

## Validation and current rollout scope

The Java kit was validated with Temurin 25 because the workspace host JDK is 21
while the project targets Java 25:

- complete `platform/java-kit` suite: 97 tests, 0 failures, 0 errors, 8
  environment-dependent skips;
- focused subscriber and metric tests cover success, duplicate/retry/DLQ
  semantics, bounded attributes, outbox sampling, failure-stage classification
  and SDK registration after an early no-op record;
- Booking Orchestration, Journey Order and Payment package successfully against
  the updated shared kit.

No deployment was performed. The metrics become runtime-visible when owning
service images are rebuilt with these commits and the metric pipeline accepts the
`train_ticket.*` families.
