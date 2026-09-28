# Low-cardinality business metrics

Last updated: 2026-09-28, Asia/Beijing.

This document defines the Prometheus contract for operational business
invariants. It is intentionally separate from Reporting's customer-facing
metrics: these series answer whether workflows are progressing and conserving
money or capacity, not what should appear in a product report.

The metric transport is already available. Services send metrics over OTLP to
`otel-collector`; its bounded exporter exposes only approved runtime families
and `train_ticket.*`, and Prometheus scrapes that aggregate on port 9464. A
service must use its existing OpenTelemetry meter rather than add a second
Prometheus server.

## Cardinality rules

Allowed labels are bounded enums or configured names:

- `workflow`, `stage`, `state`, `outcome`, `reason_code`, `operation`
- `currency`, `channel`, `travel_class`, `seat_mode`, `ticket_type`
- `queue`, `event_stream`, `consumer_group`, `event_type`,
  `source_service`, `target_service`

Do not attach order, saga, payment, refund, hold, seat, ticket, waitlist,
wallet, notification, account, traveler, request or correlation identifiers.
Do not attach raw URLs, arbitrary error messages or exception class names.

`reason_code` must come from a small owned vocabulary, and a configured name
from the list above; unknown values collapse to `OTHER` and must not be copied
from exception text. Monetary instruments
use integer minor units and always carry `currency`; PromQL must never sum
across currencies.

## Metric contract

| Area | Source metric | Type | Required labels | Meaning |
| --- | --- | --- | --- | --- |
| Order funnel | `train_ticket.order.transitions` | Counter | `stage`, `outcome`, `channel` | Attempted, accepted and terminal transitions for search, quote, order, reservation, payment and ticket stages. |
| Booking saga | `train_ticket.booking.saga.active` | UpDownCounter | `stage` | Sagas currently waiting at a stage. |
| Booking saga | `train_ticket.booking.saga.transitions` | Counter | `stage`, `outcome`, `reason_code` | Saga transition outcomes. |
| Booking saga | `train_ticket.booking.saga.oldest_age` | Gauge, seconds | `stage` | Age of the oldest nonterminal saga at that stage. |
| Booking saga | `train_ticket.booking.saga.step_due_age` | Gauge, seconds | none | Age of the oldest saga step deadline the timeout sweep has not retired, rebuilt from the indexed deadline table on every leader-elected report. An empty queue publishes no point, and neither does a deadline the sweep retires within one report interval -- which is the healthy reading: a reading means the sweep is behind. |
| Payment/refund | `train_ticket.payment.operations` | Counter | `operation`, `outcome`, `currency`, `channel` | Capture, refund, reversal and compensation operation outcomes. |
| Payment/refund | `train_ticket.payment.amount` | Counter, minor units | `operation`, `outcome`, `currency`, `channel` | Amount processed by the same operations. |
| Payment/refund | `train_ticket.payment.conservation` | Gauge, minor units | `state`, `currency` | Authoritative captured, refunded, reversed and unsettled totals for conservation checks. |
| Payment/refund | `train_ticket.payment.intent_expiry_age` | Gauge, seconds | none | Age of the oldest intent that is past its expiry and that the one-second expiry sweep has not expired yet. Rebuilt from the intent rows on every sweep, so a restart republishes the same reading; a sweep with nothing due publishes no point. |
| Capacity hold | `train_ticket.capacity.units` | Gauge | `state`, `travel_class`, `seat_mode` | Sellable, held, confirmed, released and occupied units. |
| Capacity hold | `train_ticket.capacity.hold.oldest_age` | Gauge, seconds | `state`, `travel_class` | Oldest nonterminal hold age. |
| Seat | `train_ticket.seat.allocations` | Gauge | `state`, `travel_class`, `seat_mode` | Physical seat and standing allocations by state. |
| Ticketing | `train_ticket.ticketing.transitions` | Counter | `stage`, `outcome`, `ticket_type`, `reason_code` | Issue, void, replacement and validation outcomes. |
| Ticketing | `train_ticket.ticketing.active` | UpDownCounter | `stage`, `ticket_type` | Ticketing work currently in progress. |
| Waitlist | `train_ticket.waitlist.intents` | Gauge | `state`, `travel_class` | Current waitlist intent population. |
| Waitlist | `train_ticket.waitlist.oldest_age` | Gauge, seconds | `state`, `travel_class` | Oldest actionable intent age. |
| Waitlist | `train_ticket.waitlist.promotions` | Counter | `outcome`, `reason_code`, `travel_class` | Promotion attempts and terminal outcomes. |
| Wallet | `train_ticket.wallet.balance` | Gauge, minor units | `state`, `currency` | Available, reserved, redeemed, expired and reversed balances. |
| Wallet | `train_ticket.wallet.operations` | Counter | `operation`, `outcome`, `reason_code`, `currency` | Issue, reserve, redeem, release, expire and reverse outcomes. |
| Notification | `train_ticket.notification.queue.tasks` | Gauge | `state`, `channel` | Durable delivery queue depth. |
| Notification | `train_ticket.notification.queue.oldest_age` | Gauge, seconds | `state`, `channel` | Oldest task age; this is the primary user-delay signal. |
| Notification | `train_ticket.notification.delivery_results` | Counter | `channel`, `outcome`, `reason_code` | Durable delivery results by bounded channel, outcome and reason. |
| Notification | `train_ticket.notification.retries` | Counter | `channel`, `outcome`, `reason_code` | Retry attempts, deferrals and exhausted attempts. |
| Notification | `train_ticket.notification.policy_actions` | Counter | `action`, `channel`, `outcome`, `reason_code` | Expiry and supersession policy decisions. |
| Disruption | `train_ticket.disruption.segment_signals` | Counter | `signal_type`, `outcome`, `source` | Inbound segment disruption signals by bounded type, processing outcome and source. |
| Event transport | `train_ticket.event.consumer.lag` | Gauge | `event_stream`, `consumer_group` | Redis stream entries behind the delivered position. |
| Event transport | `train_ticket.event.consumer.pending` | Gauge | `event_stream`, `consumer_group` | Pending entries owned by a consumer group. |
| Event transport | `train_ticket.event.consumer.oldest_age` | Gauge, seconds | `event_stream`, `consumer_group` | Age of the oldest undelivered or pending entry. |
| Event transport | `train_ticket.event.dlq.entries` | Gauge | `event_stream`, `consumer_group`, `event_type` | Current dead-letter population. |
| Event transport | `train_ticket.event.transport.series` | Gauge | `kind` | Series the collector publishes (`group`, `dead_letter`) and what its own bound held back (`group_dropped`, `dead_letter_dropped`). |
| Scheduled job | `train_ticket.scheduled_job.success_age` | Gauge, seconds | `scheduled_job` | Seconds since the job last succeeded, rebuilt from durable run records. A job that has never succeeded publishes nothing rather than zero. |

Every platform kit emits a smaller process-level transport set -- the subscriber
and the outbox relay of each runtime, under the same names, units and closed
vocabularies, so one rule and one dashboard cover every service:

- `train_ticket.event.consumer.messages` by `outcome`
  (`success`, `duplicate`, `retry`, `dead_letter`);
- `train_ticket.event.consumer.failures` and `.dead_letters`, whose closed
  `failure.type`/`reason` enums (`handler_exception`, `transient_result`,
  `fatal_result`, `missing_envelope`, `invalid_envelope`,
  `redelivery_exhausted`, `dlq_write`) are normalized to `reason_code` in the
  Prometheus branch;
- `train_ticket.outbox.unpublished.sample`, `.oldest_unpublished.age`,
  `.published` and `.failures` by `stage` (`select`, `publish`,
  `mark_published`).

The `train-ticket-transport` rules consume those series immediately. They are
not substitutes for Redis state: a dead-letter counter says messages were moved
over time, while `train_ticket.event.dlq.entries` is the current population.
The outbox's queue depth is the head of the relay's own bounded read, never a
`count(*)`: an exact count during a backlog is a new source of database pressure
at the moment the database is already the bottleneck, and a sample equal to the
relay's batch size already says a full batch is waiting.

Two of the vocabularies are narrower in one direction and the kits say so in
place rather than inventing a value: `missing_envelope` cannot occur in Rust,
whose message always carries its envelope field, and `redelivery_exhausted` is
reachable only where the kit has both a handler-failure cap and a slower
crash-loop backstop on Redis' own redelivery counter (Java, Go and Rust today;
Python and TypeScript retire on handler failures alone).

The Collector converts dots to underscores and adds Prometheus unit/type
suffixes. For example, `train_ticket.payment.operations` becomes
`train_ticket_payment_operations_total`, while
`train_ticket.notification.queue.oldest_age` with unit `s` becomes
`train_ticket_notification_queue_oldest_age_seconds`.

## Recording rules

`templates/_prometheus-rules.tpl` installs three groups. Runtime rules are
immediately backed by existing instrumentation:

- request rate and 5xx ratio by service;
- p95 and p99 HTTP duration by service and route template;
- database pool utilization and pending callers across Java and non-Java
  runtimes.

Transport rules are immediately backed by Java services rebuilt with the shared
event/outbox instrumentation. They expose handler outcomes, bounded failure
classes, dead-letter handoffs, relay throughput, relay failures and oldest
unpublished outbox age.

Business rules aggregate the contract above without identifiers. Until an
owning service publishes a source metric, its rule returns no series. This is
deliberate: missing instrumentation is unknown, not zero.

Useful failure-exercise queries include:

```promql
# Reservation-to-ticket funnel by outcome.
sum by (stage, outcome) (train_ticket:order_funnel_transitions:rate5m)

# Captured and refunded movement, never summed across currency.
sum by (currency, operation, outcome) (train_ticket:payment_amount:increase5m_minor_units)

# Capacity accepted without a matching allocation.
sum by (travel_class, seat_mode) (train_ticket:capacity_units:sum{state="confirmed"})
  -
sum by (travel_class, seat_mode) (train_ticket:seat_allocations:sum{state="allocated"})

# User-visible delivery delay and transport health.
train_ticket:notification_queue_oldest_age:max_seconds
train_ticket:event_consumer_oldest_age:max_seconds
train_ticket:event_dlq_entries:max
```

## Implementation ownership

The pipeline and names were committed first so service implementations could
not diverge. Every source below is now implemented and deployed; the deployed
revision and evidence are recorded in [current status](../00-current-status.md).

| Source metric | Owning implementation |
| --- | --- |
| `train_ticket.order.transitions` | Each stage is emitted by the service that serves it: `trip-planning` emits `search`, `fare-pricing` emits `quote`, `journey-order` emits `order`, and `booking-orchestration` emits `reservation`, `payment` and `ticket`. Offer management is not an emitter: it sees neither a search nor a quote, and counting its offers under either stage would have made every offer attempt look like a search. Each stage records one `attempted` and then exactly one of `accepted` or `rejected`. |
| `train_ticket.booking.saga.*` | `booking-orchestration`, rebuilt from durable saga snapshots by a leader-elected reporter. |
| `train_ticket.payment.*` | `payment` owns the domain view and the conservation gauge; `payment-channel` publishes the edge it settles; `post-sales` publishes compensation outcomes only, with no amount and no conservation series. |
| `train_ticket.capacity.*`, `train_ticket.seat.allocations` | `capacity-availability` folds pool documents; `seat-assignment` folds its allocations. Both publish a standing substitution with the same `travel_class` and `seat_mode`, so the supply disagreement is expressible as `capacity.units{state="confirmed"} - seat.allocations{state="allocated"}`. |
| `train_ticket.ticketing.*` | `entitlement-ticketing` emits issue, replacement and void; `fulfillment` emits the validation stage. |
| `train_ticket.waitlist.*` | `waitlist`, from a group-by of its durable entries. |
| `train_ticket.wallet.*` | `wallet-promotion`, from its account, instrument and reversal snapshots. |
| `train_ticket.notification.queue.*`, `.delivery_results`, `.retries`, `.policy_actions` | `notification`. Queue depth is maintained by a trigger on the delivery queue and read from that table, because a count over the queue itself is a scan of tens of millions of rows. |
| `train_ticket.disruption.segment_signals` | `disruption-recovery`, recording only the bounded classification of each inbound segment signal; unresolved segment refs count as `outcome="retry"` and are not a domain error. |
| `train_ticket.event.*` | `redis-state-collector`, one process for every stream. Its label set is as wide as the transport is, so it bounds its own output and publishes the bound it applied as `train_ticket.event.transport.series`. See [its README](../../tools/redis-state-collector/README.md). |
| `train_ticket.scheduled_job.*` | Each service that runs a sweep or a scheduled job, from the durable record of what it did. `finance-settlement` reports `success_age` per job type out of `settlement_schedule_runs`. A sweep that stops is otherwise invisible: it publishes its results and nothing else, which is how the settlement scheduler sat unregistered for ten hours without a series to say so. |

Every platform kit emits that smaller process-level transport set: the Java, Go,
Python, TypeScript and Rust subscribers and outbox relays publish the same
names, units and closed vocabularies, so one rule and one dashboard cover every
service.

Each implementation carries a cardinality test that records two different
business identifiers and proves they produce the same label set, and a restart
test for every level: a process counter reset must not be mistaken for a
business balance reset.

### Levels are read from durable rows, never adjusted in memory

Every gauge above is rebuilt from stored state on a timer rather than mirrored
from the mutation path. Two consequences are deliberate:

- A restarted process republishes the same level once its first read succeeds,
  and before that first read there is no series at all. Missing is unknown, not
  zero.
- A failed read keeps the previous reading. "The collector could not read the
  source" and "the source is empty" must not produce the same series.

The cost of that choice is the read. Two of them needed a change to be viable
on this cluster's data volume: the notification queue depth is maintained by a
database trigger because counting the queue is a sequential scan of tens of
millions of rows, and the capacity fold streams its pool documents row by row
because collecting them first is several gigabytes of expanded JSON.

### Adding a label

Two independent allowlists trim the scrape, and a label must survive both. The
Collector's OTTL branch keeps only the datapoint labels named in `keep_keys` in
`templates/_collector-configs.tpl`; Prometheus then applies its own `labelkeep`
regex in `templates/_prometheus-config.tpl`. A label named in this document but
missing from either list is published with the label stripped — the notification
`action` split, the disruption `signal_type`/`source` split and the scheduled-job
`scheduled_job` split all shipped that way once. `scheduled_job` is carried by
both lists today: it is in the collector's `keep_keys` and in the Prometheus
`labelkeep` regex. The test in
`deploy/tests/test_observability_metrics_config.py` asserts that every label the
collector keeps survives the scrape, so the two cannot drift apart again. Add a
new label to `keep_keys`, to the Prometheus `labelkeep` regex, and to that test
in the same change.
