# Reporting Event Contracts

Last updated: 2026-09-28.

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

## Subscribed streams

Reporting consumes fourteen streams, and they are the ones that carry a fact it
projects: `events:ancillary-service`, `events:booking-orchestration`,
`events:customer-service`, `events:dispatch`, `events:finance-settlement`,
`events:fulfillment`, `events:invoicing`, `events:journey-order`,
`events:payment`, `events:payment-channel`, `events:post-sales`,
`events:seat-assignment`, `events:travel-insurance` and `events:waitlist`. The
list is in `services/reporting/src/reporting/adapters/messaging/stream_config.py`.

It is not every context on the platform. It used to be, with a client-side
filter discarding the rest, but a discarded event still costs a read, a parse and
an acknowledge on a bus with one Redis thread shared by every service. Twelve of
the applied event types (`BookingConfirmed`, `CapacityUpdated`,
`SeatInventoryUpdated`, `CapacityExhausted`, `TripSearched`, `SearchPerformed`,
`OfferSearchRequested`, `PaymentDeclined`, `PaymentCaptureFailed`,
`ScalperBlocked`, `RiskBookingBlocked`, `AncillaryQuoted`) have no producer
anywhere in this repository, so the streams that would have carried only them
were cost without a fact.

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
