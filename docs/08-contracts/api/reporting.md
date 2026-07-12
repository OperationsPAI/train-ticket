# Reporting — HTTP API

Last updated: 2026-07-05

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

## Bus-only commands

| Command | Trigger | Description |
|---|---|---|
| `DefineMetric` | Admin UI | Draft a new metric definition. |
| `PublishMetricVersion` | Admin UI | Publish a metric version. |
| `RebuildReadModel` | Manual or scheduled | Rebuild a dashboard read model. |

## Open Issues

- None.
