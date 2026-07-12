# Shared Primitives

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
| `amount` | string | Decimal with scale matching currency fraction digits | `100.00` |

Money arithmetic must reject cross-currency operations at the domain level.
All monetary values use `BigDecimal` with scale set to the currency's default
fraction digits and `RoundingMode.UNNECESSARY` (or `HALF_EVEN` for division).

## Timestamps

All timestamps use `java.time.Instant` (ISO-8601 instant). Precision is
milliseconds or finer.

## Event Envelope

Every domain event carries:

| Field | Type | Description |
|---|---|---|
| `eventId` | string | Unique event identifier (UUID v7). |
| `occurredAt` | timestamp | When the event was recorded. |
| `sourceCommandId` | string | The command that produced this event. |
| `causationId` | string | The immediate cause (previous event or external fact). |
| `correlationId` | string | End-to-end trace identifier. |
| `schemaVersion` | int | Event schema version (currently 1). |
| `attributes` | map[string,string] | Extensible metadata. |

## Consumed Event Record

When a downstream context consumes an upstream event, it must record at least:

| Field | Type | Description |
|---|---|---|
| `eventId` | string | The consumed event's unique identifier (dedup key). |
| `consumedAt` | timestamp | When this context processed the event. |
| `source` | string | The upstream context name (e.g. `journey-order`, `payment`). |
| `eventType` | string | The consumed event type name. |
