# Finance Settlement Event Contracts

## RevenueRecognized

Produced when revenue is recognized for a completed fulfillment or entitlement
event.

| Field | Type | Description |
|---|---|---|
| `revenueRecognitionId` | string | Unique ID for this recognition. |
| `orderItemId` | string | The order item/business revenue reference being recognized. For order-level payment facts that do not carry a line item, this is the payment `businessRef` (order ID), not the payment intent ID. |
| `orderId` | string | The parent order. |
| `componentCode` | string | Financial component (fare, tax, fee, ancillary, discount). |
| `amount` | Money | Recognized revenue amount. |
| `recognitionPolicyVersion` | string | Version of the recognition policy applied. |
| `sourceEventId` | string | The upstream event that triggered recognition. |
| `metadata` | EventMetadata | Standard event envelope. |

## RevenueRecognitionReversed

Produced when previously recognized revenue is reversed (e.g. an approved
refund). RULING (2026-07-06): reversals are a dedicated event — `componentCode`
keeps its original enum (fare, tax, fee, ancillary, discount) and always names
the component being reversed; there is no `refund` component.

| Field | Type | Description |
|---|---|---|
| `revenueRecognitionId` | string | ID of the original recognition being reversed. |
| `orderItemId` | string | The order item whose revenue is reversed. |
| `orderId` | string | The parent order. |
| `componentCode` | string | Original financial component (fare, tax, fee, ancillary, discount). |
| `amount` | Money | Positive amount being reversed. |
| `reversalReason` | string | Why the revenue was reversed (e.g. post-sales refund applied). |
| `sourceEventId` | string | The upstream event that triggered the reversal. |
| `metadata` | EventMetadata | Standard event envelope. |

## ReconciliationCaseOpened

Produced when a reconciliation mismatch is detected.

| Field | Type | Description |
|---|---|---|
| `reconciliationCaseId` | string | Unique ID for this reconciliation case. |
| `orderId` | string | The order involved (if applicable). |
| `paymentIntentId` | string | The payment intent involved (if applicable). |
| `differenceType` | string | Type: missing-in-channel, missing-in-platform, amount-mismatch, currency-mismatch, duplicate, late-payment, refund-lag. |
| `expectedAmount` | Money | Amount expected by the platform. |
| `actualAmount` | Money | Amount observed from channel/provider. |
| `description` | string | Human-readable explanation. |
| `metadata` | EventMetadata | Standard event envelope. |

## ReconciliationCaseResolved

Produced when a reconciliation case is resolved.

| Field | Type | Description |
|---|---|---|
| `reconciliationCaseId` | string | Unique ID for the case. |
| `resolution` | string | Resolution: auto-resolved, manual-resolved, rejected. |
| `resolutionNote` | string | Explanation of the resolution. |
| `metadata` | EventMetadata | Standard event envelope. |

## ReconciliationCompleted

Produced when finance reconciliation completes for an order/payment fact.

| Field | Type | Description |
|---|---|---|
| `reconciliationId` | string | Unique ID for this reconciliation result. |
| `orderId` | string | The order reconciled. |
| `paymentIntentId` | string | The payment intent involved, if available. |
| `reconciliationStatus` | string | Result status (`MATCHED`). |
| `expectedAmount` | Money | Amount expected by finance. |
| `actualAmount` | Money | Amount observed from the matched upstream facts. |
| `matchedRevenueRecognitionIds` | string[] | Revenue records included in the match. |
| `sourceEventIds` | string[] | Upstream event IDs included in the match. |
| `metadata` | EventMetadata | Standard event envelope. |

## InvoiceGenerated

Produced when an invoice is generated from recognized revenue.

| Field | Type | Description |
|---|---|---|
| `invoiceId` | string | Unique invoice ID. |
| `orderId` | string | The invoiced order. |
| `invoiceNumber` | string | Human/business invoice number. |
| `totalAmount` | Money | Total positive invoice amount. |
| `revenueRecognitionIds` | string[] | Revenue records included in this invoice. |
| `generatedAt` | timestamp | When the invoice was generated. |
| `metadata` | EventMetadata | Standard event envelope. |

## SettlementViewRebuilt

Produced when a settlement view is rebuilt from the event log.

| Field | Type | Description |
|---|---|---|
| `settlementViewId` | string | Unique ID for this view rebuild. |
| `viewType` | string | Type of view rebuilt (revenue, reconciliation, settlement). |
| `eventCount` | int | Number of events replayed. |
| `metadata` | EventMetadata | Standard event envelope. |
