# Payment — Events & Commands

Last updated: 2026-07-10

ADR-0003 Wave A same-wave increment（需同波实现）: Payment events that represent channel-backed
capture/refund outcomes carry `channelRef` from Payment Channel. This requires
Payment implementation changes in channel enum validation, event payload
serializers/deserializers, capture/refund state guards, and read-model
projection; it is not a docs-only increment.

## Published Events

### PaymentIntentCreated

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order, booking-orchestration |
| **Trigger** | `CreatePaymentIntent` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Canonical payment intent ID (`pi-<uuid>`). |
| `businessRef` | string | yes | Reference to the business object. |
| `purpose` | string | yes | Payment purpose (e.g. `purchase`). |
| `amount` | `Money` | yes | Amount to collect. |
| `payerRef` | string | yes | Payer identifier. |
| `idempotencyKey` | string | yes | Idempotency key. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |

### PaymentAuthorized

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order |
| **Trigger** | Payment channel authorisation successful. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |
| `authorizedAmount` | `Money` | yes | Amount authorised. |
| `channel` | string | yes | Payment channel. |
| `channelTransactionId` | string | yes | Channel transaction reference. |
| `channelRef` | object | yes | Payment Channel reference with `channel`, `channelOrderId`, and `channelTransactionId`. |

### PaymentCaptured

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order, booking-orchestration |
| **Trigger** | Payment capture confirmed by channel. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |
| `businessRef` | string | yes | Business reference (order ID) from the payment intent; consumers correlate the payment to the order with this field. |
| `capturedAmount` | `Money` | yes | Amount captured. |
| `channel` | string | yes | Payment channel. |
| `channelTransactionId` | string | yes | Channel transaction reference. |
| `channelRef` | object | yes | Payment Channel reference with `channel`, `channelOrderId`, and `channelTransactionId`. |

### PaymentFailed

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order |
| **Trigger** | Payment processing failed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |
| `reasonCode` | string | yes | Failure reason code. |
| `retryable` | bool | yes | Whether retryable. |

### PaymentIntentCancelled

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order |
| **Trigger** | `CancelPaymentIntent` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |
| `reason` | string | yes | Cancellation reason. |

### PaymentIntentExpired

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | none |
| **Trigger** | Payment intent TTL elapsed without capture. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |

### PaymentTimedOut

Published together with `PaymentIntentExpired` on the same expiry. This is the
order-facing one of the pair: it carries the business reference, so it is what a
consumer that has to resolve the originating order subscribes to.
`PaymentIntentExpired` is intent-scoped and carries no order reference.

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order, booking-orchestration |
| **Trigger** | Payment intent TTL elapsed without capture. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |
| `intentId` | `PaymentIntentId` | yes | Alias of `paymentIntentId`. |
| `businessRef` | string | yes | Originating business reference (the journey order ID). |
| `orderId` | string | yes | Alias of `businessRef`. |
| `expiresAt` | timestamp | yes | The deadline that elapsed. |
| `reason` | string | yes | Expiry reason (`TIMEOUT`). |

### RefundSettled

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | journey-order, post-sales, notification |
| **Trigger** | Refund completed by channel. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `refundId` | `RefundId` | yes | Refund ID. |
| `paymentIntentId` | `PaymentIntentId` | yes | Original payment intent. |
| `amount` | `Money` | yes | Amount refunded. |
| `channelRef` | object | yes | Payment Channel refund reference with `channel`, `channelOrderId`, `channelRefundId`, `channelTransactionId`, and `channelRefundTransactionId` when available. |

### RefundFailed

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | post-sales |
| **Trigger** | Refund processing failed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `refundId` | `RefundId` | yes | Refund ID. |
| `paymentIntentId` | `PaymentIntentId` | yes | Original payment intent. |
| `reason` | string | yes | Failure reason. |
| `channelRef` | object | no | Payment Channel refund reference when failure happened after channel handoff. |

### LatePaymentDetected

| Field | Description |
|---|---|
| **Producer** | payment |
| **Consumers** | none |
| **Trigger** | Channel-confirmed capture arrived against a CANCELLED or EXPIRED payment intent. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `latePaymentCaseId` | string | yes | Late payment case ID (`lpc-` + 32 hex chars), folded from `paymentIntentId`, `channel`, and `channelTransactionId` so repeated reports of one late collection resolve to a single case. |
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. The intent stays terminal; a late capture never revives it. |
| `capturedAmount` | `Money` | yes | Amount collected by the channel. |
| `channel` | string | yes | Payment channel. |
| `channelTransactionId` | string | yes | Channel transaction reference. |
