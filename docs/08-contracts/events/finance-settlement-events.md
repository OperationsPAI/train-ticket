# Finance Settlement Event Contracts

Last updated: 2026-09-24.

Runtime baseline: Helm revision 279.
Remaining work is tracked in [TODO](../../TODO.md).

Finance Settlement consumes `events:payment-channel`, validates statement and discrepancy payloads, and deduplicates by envelope `eventId`.
Channel difference types are mapped to Finance difference strings, including `STATUS_MISMATCH` as `status-mismatch`.
Wallet compensation approvals skip refund projection and compensation completion skips revenue reversal.
Its issuance cost is recorded from `BenefitIssued.issuedAmount` only, with account, case and benefit attribution.
The benefit cost query also contains subsequent lifecycle deltas; these are described in [financial calculations](../../10-domain-enrichment/finance-settlement-enrichment.md).

## RevenueRecognized

Ordinary ticket revenue is recognized from `PaymentCaptured` using `payment-capture-v1`.
Change payments are assigned to their original order after checking the Post Sales case and captured payment facts.
The current change capture component is `change-payment`.
Ancillary revenue is recognized from its fulfillment fact.

| Field | Type | Description |
|---|---|---|
| `revenueRecognitionId` | string | Unique ID for this recognition. |
| `orderItemId` | string | Business revenue reference: ordinary ticket capture uses the recorded supplier reference or order ID, change capture uses the payment intent ID, and ancillary recognition uses its item ID. |
| `orderId` | string | The parent order. |
| `componentCode` | string | Financial component, including `fare`, `tax`, `fee`, `ancillary`, `discount` and `change-payment`. |
| `amount` | Money | Recognized revenue amount. |
| `recognitionPolicyVersion` | string | Version of the recognition policy applied. |
| `sourceEventId` | string | The upstream event that triggered recognition. |
| `metadata` | EventMetadata | Standard event envelope. |

## RevenueRecognitionReversed

Produced when a recorded revenue amount is reversed for an applied refund or checked financial adjustment.
`componentCode` identifies the original revenue component.
Compensation approval and completion do not produce a reversal.

| Field | Type | Description |
|---|---|---|
| `revenueRecognitionId` | string | ID of the original recognition being reversed. |
| `orderItemId` | string | The order item whose revenue is reversed. |
| `orderId` | string | The parent order. |
| `componentCode` | string | Original financial component, including `change-payment` for fare difference recognition. |
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
| `differenceType` | string | Type: missing-in-channel, missing-in-platform, amount-mismatch, currency-mismatch, status-mismatch, duplicate, late-payment, refund-lag. |
| `expectedAmount` | Money | Amount expected by the platform. |
| `actualAmount` | Money | Amount observed from channel/provider. |
| `description` | string | Human-readable explanation. For Payment Channel input, include the statement/discrepancy references without raw PII or unmasked documents. |
| `channelStatementId` | string | Optional Payment Channel statement ID when the case is opened from a channel statement discrepancy. |
| `paymentChannelDiscrepancyId` | string | Optional Payment Channel discrepancy ID when linked. |
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
| `sourceEventIds` | string[] | Upstream event IDs included in the match, including Payment Channel statement event IDs when applicable. |
| `channelStatementId` | string | Optional Payment Channel statement ID reconciled. |
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
