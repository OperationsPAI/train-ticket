# Reporting HTTP API

Last updated: 2026-09-23

## Overview

Reporting manages metric definitions, dashboard read models, and report
queries. It consumes events from all contexts and provides read-only query
endpoints. All write commands are **bus-only**.


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

**GET** `/api/v1/metrics/revenue?groupBy=route&limit=20`

`groupBy` supports `route`, `seat_class`, `channel`, `passenger_type`, and `time_period` for monetary reports.
All monetary revenue fields use `PaymentCaptured` only and represent gross captured payment amount.
Refunds, `RevenueRecognized`, `RevenueRecognitionReversed`, and `PaymentSucceeded` contribute no monetary revenue to these metrics.
This definition also applies to `routeRevenue` from `/api/v1/metrics/routes` and `revenue_per_hour` from `/api/v1/metrics/operational` and trend reports.
Gross captured payment amount does not represent net revenue or accounting recognized revenue.
The default metric catalog describes this meaning; existing stored metric descriptions are not automatically overwritten.

| Field | Meaning |
|---|---|
| `generatedAt` | Response timestamp; it is not a materialized view freshness watermark. |
| `groupBy` | Requested dimension. |
| `items[].revenue` | Gross captured amount for the dimension value and currency. |
| `items[].count` | Captured event count in the PostgreSQL projection; a missing amount contributes zero amount and one count. |
| `items[].yieldPerKm` | Captured amount divided by the sum of recorded distance, or zero when the distance sum is zero. |
| `items[].ancillaryAttachRate` | Captured rows with the ancillary flag divided by captured row count. |
| `items[].insuranceAttachRate` | Captured rows with the insurance flag divided by captured row count. |
| `totalRevenue` | Sum of returned items after the result limit, with the first item's currency or USD for an empty result. |

PostgreSQL projection groups always include currency.
The response's scalar `totalRevenue` does not convert currencies and must not be interpreted as a valid combined monetary total when returned items contain multiple currencies.
Missing dimension values appear as `unknown`; `time_period` uses UTC hour labels such as `2026-09-23T15:00:00Z`.
The report covers stored history and reads the last completed materialized refresh.
Refresh work runs when requests are pending and waits 30 seconds after each completed worker iteration.
The in memory implementation requires one currency and excludes events whose amount is absent from its monetary count.
Requests using `source_context` or `event_type` return event count reports with zero monetary fields.

## Bus-only commands

| Command | Trigger | Description |
|---|---|---|
| `DefineMetric` | Admin UI | Draft a new metric definition. |
| `PublishMetricVersion` | Admin UI | Publish a metric version. |
| `RebuildReadModel` | Manual or scheduled | Rebuild a dashboard read model. |

## Open Issues

- None.
