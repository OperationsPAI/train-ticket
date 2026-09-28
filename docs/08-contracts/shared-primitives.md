# Shared Primitives

Last updated: 2026-09-28.

## Cross-Context IDs

| Field | Type | Format | Example |
|---|---|---|---|
| `orderId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c123` |
| `accountId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c456` |
| `paymentIntentId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c789` |
| `refundId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0cab` |
| `offerId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0cdef` |
| `entitlementId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c111` |
| `eventId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c222` |
| `sourceCommandId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c333` |
| `correlationId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c444` |
| `causationId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c555` |
| `revenueRecognitionId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c666` |
| `reconciliationCaseId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c777` |
| `settlementViewId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c888` |
| `consumedEventLogId` | string | UUID v7 | `0194f2e0-7b3e-7610-0284-5c26e8b0c999` |

## Money

| Field | Type | Format | Example |
|---|---|---|---|
| `currency` | string | ISO 4217 | `CNY` |
| `minorUnits` | integer | Signed amount in the currency's minor unit | `10000` |

Money is an integer minor-unit amount plus its currency, on the wire and in the
shared kernel (`Money.amount_minor`, serialized as `minorUnits`). No decimal
strings or floating-point amounts are used. Money arithmetic must reject
cross-currency operations at the domain level.

## Timestamps

Timestamps are RFC3339 UTC strings on the wire. Java services use
`java.time.Instant` internally. Precision is milliseconds or finer.

## Event Envelope

Every domain event carries:

| Field | Type | Required | Description |
|---|---|---|---|
| `eventId` | string | yes | Unique event identifier (UUID v7, `evt-` prefixed). |
| `eventType` | string | yes | Event type name. |
| `schemaVersion` | int | yes | Event schema version (currently 1). |
| `producer` | string | yes | Producing context name. |
| `causationId` | string | no | The immediate cause (previous event or external fact). |
| `correlationId` | string | yes | End-to-end trace identifier. |
| `occurredAt` | RFC3339 UTC string | yes | When the event was recorded. |
| `payload` | object | yes | Event-specific fields. |
| `traceparent` | string | no | W3C Trace Context, carried when OTel tracing is enabled. |
| `tracestate` | string | no | W3C Trace Context state; only ever present with `traceparent`. |

## Consumed Event Record

When a downstream context consumes an upstream event, it must record at least:

| Field | Type | Description |
|---|---|---|
| `event_id` | string | The consumed event's unique identifier (dedup key, primary key). |
| `stream` | string | The source stream the event arrived on (e.g. `events:payment`). |
| `processed_at` | timestamp | When this context processed the event. |
