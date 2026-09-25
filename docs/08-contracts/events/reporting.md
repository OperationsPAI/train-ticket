# Reporting Event Contracts

Last updated: 2026-09-25.

Implementation baseline: authoritative revenue facts and migrations 007/008.
Remaining work is tracked in [TODO](../../TODO.md).

Monetary metrics consume `PaymentCaptured` only and represent gross captured amount.
Other events can affect counts, dashboard rebuilds and anomaly evaluation without changing that amount.
The background worker persists `ReadModelRebuilt` and `AnomalyDetected` results and notification requests for urgent anomalies.
Current aggregation and query limits are described in [Reporting](../../02-domains/reporting.md).

Reporting also consumes authoritative attribution facts from `JourneyOrderCreated`,
`PostSalesCaseOpened`/`PostSalesRequested`, `SegmentReservationRequested`,
`SeatAllocated`/`StandingAssigned`, ancillary item lifecycle events, and insurance
policy events. These facts resolve `PaymentCaptured.businessRef`; Reporting does
not treat route, seat class, traveler type, or attachment fields on the payment
payload as authoritative.

## MetricDefined

Produced when a new metric definition is drafted.

| Field | Type | Description |
|---|---|---|
| `metricId` | string | Unique metric identifier. |
| `name` | string | Human-readable metric name. |
| `description` | string | Business definition and calculation description. |
| `owner` | string | Owner/team responsible for the metric. |
| `category` | string | Metric category (operational, financial, quality, customer_service, supplier, notification). |
| `granularity` | string | Aggregation granularity (daily, weekly, monthly, quarterly, yearly, cumulative). |
| `version` | string | Semantic version of this metric definition. |
| `expression` | string | Calculation expression or query logic. |
| `dependsOnMetricIds` | string[] | Metrics this definition depends on. |
| `sourceLineage` | string[] | Source data lineage (event streams, read models). |
| `metadata` | EventMetadata | Standard event envelope. |

## MetricVersionPublished

Produced when a metric definition version is published.

| Field | Type | Description |
|---|---|---|
| `metricId` | string | Unique metric identifier. |
| `name` | string | Human-readable metric name. |
| `version` | string | Published version. |
| `publishedAt` | timestamp | When the version was published. |
| `expression` | string | Calculation expression at publish time. |
| `previousStatus` | string | Status before publishing (draft, in_review, approved). |
| `metadata` | EventMetadata | Standard event envelope. |

## ReadModelRebuilt

Produced when a dashboard read model or funnel view is rebuilt.

| Field | Type | Description |
|---|---|---|
| `dashboardId` | string | Dashboard or funnel identifier. |
| `rebuildId` | string | Unique rebuild run identifier. |
| `rebuiltAt` | timestamp | When the rebuild completed. |
| `eventCount` | int | Number of events replayed. |
| `digest` | string | Content digest of the rebuilt snapshot. |
| `metadata` | EventMetadata | Standard event envelope. |
