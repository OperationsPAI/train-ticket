# Upstream Events Consumed by Finance Settlement

## Payment Events

### PaymentCaptured

Produced by Payment context when a payment is successfully captured.

| Field | Type | Description |
|---|---|---|
| `paymentIntentId` | string | Unique payment intent identifier. |
| `orderId` | string | The order being paid for. |
| `accountId` | string | The paying account. |
| `amount` | Money | Captured amount. |
| `currency` | string | ISO 4217 currency code. |
| `channelRef` | string | Payment channel reference. |
| `metadata` | EventMetadata | Standard event envelope. |

### RefundSettled

Produced by Payment context when a refund has been settled.

| Field | Type | Description |
|---|---|---|
| `refundId` | string | Unique refund identifier. |
| `paymentIntentId` | string | Original payment intent. |
| `orderId` | string | The order being refunded. |
| `amount` | Money | Refunded amount. |
| `reason` | string | Refund reason code. |
| `metadata` | EventMetadata | Standard event envelope. |

## Journey Order Events

### JourneyOrderCreated

Produced when a new order is created.

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Order identifier. |
| `accountId` | string | Account that placed the order. |
| `offerId` | string | Offer snapshot reference. |
| `monetarySummary` | MonetarySummary | Order-level monetary summary. |
| `metadata` | EventMetadata | Standard event envelope. |

### JourneyOrderConfirmed

Produced when an order is fully confirmed.

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Order identifier. |
| `accountId` | string | Account identifier. |
| `monetarySummary` | MonetarySummary | Confirmed monetary summary. |
| `metadata` | EventMetadata | Standard event envelope. |

### JourneyOrderCancelled

Produced when an order is cancelled.

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Order identifier. |
| `accountId` | string | Account identifier. |
| `cancellationReason` | string | Reason for cancellation. |
| `metadata` | EventMetadata | Standard event envelope. |

### JourneyOrderPostSalesAdjusted

Produced when a post-sales adjustment changes the order monetary summary.

| Field | Type | Description |
|---|---|---|
| `orderId` | string | Order identifier. |
| `accountId` | string | Account identifier. |
| `postSalesCaseId` | string | The post-sales case identifier. |
| `monetarySummary` | MonetarySummary | Updated monetary summary. |
| `metadata` | EventMetadata | Standard event envelope. |

## Post Sales Events

### PostSalesApplied

Produced when a post-sales decision is fully applied.

| Field | Type | Description |
|---|---|---|
| `postSalesCaseId` | string | Unique case identifier. |
| `orderId` | string | The affected order. |
| `refundDecision` | RefundDecision | Refund decision details. |
| `metadata` | EventMetadata | Standard event envelope. |

## Entitlement & Ticketing Events

### EntitlementIssued

Produced when a ticket entitlement is issued.

| Field | Type | Description |
|---|---|---|
| `entitlementId` | string | Entitlement identifier. |
| `orderId` | string | The parent order. |
| `orderItemId` | string | The specific order item. |
| `segmentRef` | string | Transport segment reference. |
| `metadata` | EventMetadata | Standard event envelope. |

### EntitlementVoided

Produced when a ticket entitlement is voided.

| Field | Type | Description |
|---|---|---|
| `entitlementId` | string | Entitlement identifier. |
| `orderId` | string | The parent order. |
| `reason` | string | Void reason. |
| `metadata` | EventMetadata | Standard event envelope. |
