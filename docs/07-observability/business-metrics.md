# Low-cardinality business metrics

Updated: 2026-09-25, Asia/Beijing.

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
- `queue`, `event_stream`, `consumer_group`, `event_type`
- `source_service`, `target_service`

Do not attach order, saga, payment, refund, hold, seat, ticket, waitlist,
wallet, notification, account, traveler, request or correlation identifiers.
Do not attach raw URLs, arbitrary error messages or exception class names.

`reason_code` must come from a small owned vocabulary. Unknown values collapse
to `OTHER`; they must not be copied from exception text. Monetary instruments
use integer minor units and always carry `currency`; PromQL must never sum
across currencies.

## Metric contract

| Area | Source metric | Type | Required labels | Meaning |
| --- | --- | --- | --- | --- |
| Order funnel | `train_ticket.order.transitions` | Counter | `stage`, `outcome`, `channel` | Attempted, accepted and terminal transitions for search, quote, order, reservation, payment and ticket stages. |
| Booking saga | `train_ticket.booking.saga.active` | UpDownCounter | `stage` | Sagas currently waiting at a stage. |
| Booking saga | `train_ticket.booking.saga.transitions` | Counter | `stage`, `outcome`, `reason_code` | Saga transition outcomes. |
| Booking saga | `train_ticket.booking.saga.oldest_age` | Gauge, seconds | `stage` | Age of the oldest nonterminal saga at that stage. |
| Payment/refund | `train_ticket.payment.operations` | Counter | `operation`, `outcome`, `currency`, `channel` | Capture, refund, reversal and compensation operation outcomes. |
| Payment/refund | `train_ticket.payment.amount` | Counter, minor units | `operation`, `outcome`, `currency`, `channel` | Amount processed by the same operations. |
| Payment/refund | `train_ticket.payment.conservation` | Gauge, minor units | `state`, `currency` | Authoritative captured, refunded, reversed and unsettled totals for conservation checks. |
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
| Notification | `train_ticket.notification.retries` | Counter | `channel`, `outcome`, `reason_code` | Retry attempts, deferrals and exhausted attempts. |
| Event transport | `train_ticket.event.consumer.lag` | Gauge | `event_stream`, `consumer_group` | Redis stream entries behind the delivered position. |
| Event transport | `train_ticket.event.consumer.pending` | Gauge | `event_stream`, `consumer_group` | Pending entries owned by a consumer group. |
| Event transport | `train_ticket.event.consumer.oldest_age` | Gauge, seconds | `event_stream`, `consumer_group` | Age of the oldest undelivered or pending entry. |
| Event transport | `train_ticket.event.dlq.entries` | Gauge | `event_stream`, `consumer_group`, `event_type` | Current dead-letter population. |

The Collector converts dots to underscores and adds Prometheus unit/type
suffixes. For example, `train_ticket.payment.operations` becomes
`train_ticket_payment_operations_total`, while
`train_ticket.notification.queue.oldest_age` with unit `s` becomes
`train_ticket_notification_queue_oldest_age_seconds`.

## Recording rules

`templates/_prometheus-rules.tpl` installs two groups. Runtime rules are
immediately backed by existing instrumentation:

- request rate and 5xx ratio by service;
- p95 and p99 HTTP duration by service and route template;
- database pool utilization and pending callers across Java and non-Java
  runtimes.

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

## Implementation backlog

The pipeline and names are committed first so service implementations cannot
diverge. The following instrumentation remains service-owned work:

- Journey Order and Booking Orchestration: order funnel and saga instruments.
- Payment and Payment Channel: operation counters, amount counters and
  conservation gauges; Post Sales contributes compensation outcomes only.
- Capacity Availability and Seat Assignment: capacity, hold and allocation
  gauges. Keep service/date/segment and physical seat identifiers out of labels.
- Entitlement Ticketing and Fulfillment: ticket lifecycle instruments.
- Waitlist: intent age, population and promotion outcomes.
- Wallet Promotion: balance conservation and operation outcomes.
- Notification: durable queue depth, oldest task age and bounded retry outcomes.
- Shared Redis transport: consumer lag, pending age and DLQ population. This
  belongs in one collector/exporter component, not one copy per subscriber.

Each implementation must add a cardinality test that records two different
business identifiers and proves they produce the same label set. It must also
test restart reconstruction for gauges derived from durable state; a process
counter reset must not be mistaken for a business balance reset.
