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
| **Consumers** | journey-order |
| **Trigger** | Payment intent TTL elapsed without capture. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | `PaymentIntentId` | yes | Payment intent ID. |

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
