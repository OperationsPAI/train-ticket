# Reporting HTTP API

Last updated: 2026-09-25.

Implementation baseline: authoritative revenue facts and migrations 007/008.
Remaining work is tracked in [TODO](../../TODO.md).

## Overview

Reporting manages metric definitions, dashboard read models and report queries.
It consumes configured event streams and exposes HTTP query endpoints.
The application also maintains dashboard rebuilds and anomalies through its background projection worker.


Field shapes reference docs/08-contracts/shared-primitives.md for IDs
and timestamps.
## Query Endpoints

### List Metrics

**GET** `/api/v1/metrics?category=operational&limit=20&offset=0`

**Response (200):** Paginated response with metric definitions.

### Get Metric

**GET** `/api/v1/metrics/{metricId}`

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `metricId` | string | Unique metric identifier. |
| `name` | string | Human-readable name. |
| `description` | string | Business definition. |
| `owner` | string | Owner team. |
| `category` | string | Category. |
| `granularity` | string | Aggregation granularity. |
| `version` | string | Current version. |
| `expression` | string | Calculation expression. |

**Error codes:** `NOT_FOUND`

### Query Dashboard

**GET** `/api/v1/dashboards/{dashboardId}`

**Response (200):** Dashboard read model snapshot.

**Error codes:** `NOT_FOUND`

### List Dashboard Rebuilds

**GET** `/api/v1/dashboards/{dashboardId}/rebuilds?limit=20&offset=0`

**Response (200):** Paginated list of rebuild runs.

Projection refresh requests are persisted with the consumed event.
Dashboard rebuilds, anomaly evaluation, and revenue view refreshes run in a background worker.
Each refresh includes all pending event generations recorded at its start.
Events accepted during a refresh remain pending for the next refresh.
`REPORTING_CONSUMERS_PER_STREAM` controls concurrent event consumers per stream and defaults to 4.

### Captured Payment Report

**GET** `/api/v1/metrics/revenue?groupBy=route&limit=20&offset=0`

`groupBy` supports `route`, `seat_class`, `channel`, `traveler_type`, and `time_period` for monetary reports.
All monetary revenue fields use `PaymentCaptured` only and represent gross captured payment amount.
Refunds, `RevenueRecognized`, `RevenueRecognitionReversed`, and `PaymentSucceeded` contribute no monetary revenue to these metrics.
This definition also applies to `revenue_per_hour` from `/api/v1/metrics/operational` and trend reports.
Gross captured payment amount does not represent net revenue or accounting recognized revenue.
The default metric catalog describes this meaning; existing stored metric descriptions are not automatically overwritten.

| Field | Meaning |
|---|---|
| `generatedAt` | Response timestamp; it is not a materialized view freshness watermark. |
| `groupBy` | Requested dimension. |
| `total` | Number of grouped rows before pagination. |
| `limit` | Requested maximum number of grouped rows in `items`. |
| `offset` | Requested grouped-row offset. |
| `items[].revenue` | Gross captured amount for the dimension value and currency. |
| `items[].count` | Count of valid captured payments with an amount. A `PaymentCaptured` fact with no amount is retained for audit but contributes neither amount nor count. |
| `items[].yieldPerKm` | Captured amount divided by the sum of recorded distance, or zero when the distance sum is zero. |
| `items[].ancillaryAttachRate` | Captured payments whose resolved order has an authoritative non-insurance ancillary purchase, divided by captured payment count in the group. |
| `items[].insuranceAttachRate` | Captured payments whose resolved order has an authoritative insurance policy/item, divided by captured payment count in the group. |
| `totalRevenue` | Complete filtered-result totals before pagination, one Money value per currency ordered by ISO currency code. An empty result returns `[]`. |

PostgreSQL projection groups always include currency.
`totalRevenue` and `revenue_per_hour` preserve each currency as a separate Money value and never perform implicit currency conversion.
Only `items` is paginated. Changing `limit` or `offset`, including requesting an offset beyond the last row, does not change `totalRevenue` or `total`.
Items are ordered by currency, then revenue descending, then dimension value; this gives stable pagination.
Trend points for `revenue_per_hour` expose the same array in `points[].values`; non-monetary trend points continue to use `points[].value`.
`route` values are accepted Journey Order `segments[].segmentRef`; `traveler_type` values are accepted Journey Order traveler types.
`seat_class` uses Booking Orchestration `classRef`, superseded by Seat Assignment `requestedClassRef` for the same order/segment/traveler key.
Purchase payments resolve directly by order ID, change payments through Post Sales case scope, and ancillary payments through Ancillary Service item scope.
Fields with the same names on `PaymentCaptured` are ignored because Payment does not own those facts.
When a payment covers multiple values, its amount is split in minor units and remainder units are assigned by stable value/key order; grouped amounts for every dimension exactly preserve each currency total.
Missing authoritative relationships appear as `unknown`; `time_period` uses UTC hour labels such as `2026-09-23T15:00:00Z`.
The report covers stored history and reads the last completed materialized refresh.
Refresh work runs when requests are pending and waits 30 seconds after each completed worker iteration.
PostgreSQL and the in-memory implementation both exclude a capture whose amount is absent from monetary totals and payment counts.
Requests using `source_context` or `event_type` return event count reports with zero monetary fields.
For those reports, `total` is the number of grouped values and `eventCount` is the complete number of source events before pagination.

### Route Metrics

**GET** `/api/v1/metrics/routes?limit=20&offset=0`

The endpoint returns route/service-date groups observed in the preceding 24 hours.
`items` is ordered by `serviceDate` descending and `routeId` ascending; `total` is the complete group count and `windowSeconds` is `86400`.
Each item contains demand, booking/search counts, latest confirmed/capacity facts, fill rate, and seat utilization by class.
It does not contain `routeRevenue`; query `/api/v1/metrics/revenue?groupBy=route` for monetary route attribution.

Operational metrics are calculated by PostgreSQL over complete indexed time ranges and are not capped by a row count.
The operational response does not embed route rows; route detail is available from the independently paginated `/api/v1/metrics/routes` endpoint.
`orders_per_second` is the rolling average order-event rate over the preceding 60 seconds: matching event count divided by 60, rounded to four decimal places.
It counts order creation facts (`JourneyOrderCreated` and `OrderCreated`) and does not double count later confirmation facts.
`revenue_per_hour`, booking latency percentiles, and `scalper_block_rate` use the preceding hour; `refund_rate` uses the preceding 24 hours; `contextRollups` use the preceding five minutes.

### Metric Trends

**GET** `/api/v1/metrics/trends`

The response contains exactly 60 complete one-minute UTC buckets over the half-open interval `[window.start, window.end)`; `window.end` is the start of the current minute, so the incomplete minute is excluded.
Every series emits all 60 buckets, including zero/empty buckets.

| Metric | Point value | Unit |
|---|---|---|
| `orders_per_second` | Order creation count in that minute divided by 60, rounded to four decimal places. | `orders_per_second` |
| `gross_captured_amount` | `values`: Money array grouped by currency for `PaymentCaptured` in that minute. | `money_per_minute` |
| `refund_rate` | Refund event count divided by order creation count in that minute; zero when no orders exist. | `ratio` |

## Bus-only commands

| Command | Trigger | Description |
|---|---|---|
| `DefineMetric` | Admin UI | Draft a new metric definition. |
| `PublishMetricVersion` | Admin UI | Publish a metric version. |
| `RebuildReadModel` | Manual or scheduled | Rebuild a dashboard read model. |
