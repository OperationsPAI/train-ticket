# Journey Order — Events & Commands

Last updated: 2026-07-04

## Published Events

### JourneyOrderCreated

| Field | Description |
|---|---|
| **Producer** | journey-order |
| **Consumers** | booking-orchestration, notification, post-sales, payment |
| **Trigger** | `CreateJourneyOrder` command processed with valid `OfferAcceptanceToken`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | `JourneyOrderId` | yes | Canonical order ID (`ord-<uuid>`). |
| `accountId` | string | yes | Owning account. |
| `offerId` | `OfferId` | yes | Source offer ID. |
| `monetarySummary` | `MonetarySummary` | yes | Price breakdown at creation. |
| `travelerRefs` | `TravelerRef[]` | yes | Traveler references (structured form, see shared-primitives.md section 2a). |
| `segmentRefs` | string[] | yes | Segment references. |
| `createdAt` | RFC3339 UTC | yes | Order creation time. |
| `sourceIp` | string | no | First hop from `X-Forwarded-For`/real client IP when available, for downstream risk velocity analysis. |

**MonetarySummary:**

| Field | Type | Required | Description |
|---|---|---|---|
| `subtotal` | `Money` | yes | Sum of item prices. |
| `total` | `Money` | yes | Total including taxes and fees. |
| `currency` | string | yes | ISO-4217 currency code. |

**Idempotency/Ordering Notes:**
- Idempotent on `idempotencyKey` (composed from `accountId:offerId:clientRequestId`).
- `JourneyOrderCreated` is emitted exactly once per unique idempotency key.

### JourneyOrderPendingPayment

| Field | Description |
|---|---|
| **Producer** | journey-order |
| **Consumers** | payment, notification |
| **Trigger** | Booking and capacity summaries accepted; order moves to PENDING_PAYMENT state. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | `JourneyOrderId` | yes | Order ID. |
| `accountId` | string | yes | Owning account. |
| `paymentPurpose` | string | yes | Reason for payment (e.g. `purchase`). |
| `monetarySummary` | `MonetarySummary` | yes | Amount to pay. |

### JourneyOrderPaymentRecorded

| Field | Description |
|---|---|
| **Producer** | journey-order |
| **Consumers** | booking-orchestration, notification |
| **Trigger** | Payment captured and recorded against the order. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | `JourneyOrderId` | yes | Order ID. |
| `accountId` | string | yes | Owning account. |
| `paymentIntentId` | `PaymentIntentId` | yes | Reference to the payment intent. |

### JourneyOrderConfirmed

| Field | Description |
|---|---|
| **Producer** | journey-order |
| **Consumers** | notification, entitlement-ticketing, post-sales |
| **Trigger** | All confirmation conditions satisfied (booking, capacity, payment, entitlement). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | `JourneyOrderId` | yes | Order ID. |
| `accountId` | string | yes | Owning account. |
| `monetarySummary` | `MonetarySummary` | yes | Final monetary summary. |
| `confirmedAt` | RFC3339 UTC | yes | Confirmation time. |

### JourneyOrderCancelled

| Field | Description |
|---|---|
| **Producer** | journey-order |
| **Consumers** | booking-orchestration, payment, notification, post-sales |
| **Trigger** | Order cancelled before completion. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | `JourneyOrderId` | yes | Order ID. |
| `accountId` | string | yes | Owning account. |
| `reason` | string | yes | Cancellation reason. |

### JourneyOrderPostSalesAdjusted

| Field | Description |
|---|---|
| **Producer** | journey-order |
| **Consumers** | notification, payment |
| **Trigger** | Post-sales case applied, order items modified. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | `JourneyOrderId` | yes | Order ID. |
| `accountId` | string | yes | Owning account. |
| `postSalesCaseId` | `PostSalesCaseId` | yes | Reference to the post-sales case. |
| `monetarySummary` | `MonetarySummary` | yes | Updated monetary summary. |

## Accepted Commands

### CreateJourneyOrder

| Field | Description |
|---|---|
| **Sender** | API gateway / UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Owning account. |
| `channelRef` | string | yes | Sales channel. |
| `clientRequestId` | string | yes | Client-generated idempotency key. |
| `offerSnapshot` | `OfferSnapshotRef` | yes | Reference to the accepted offer. |
| `travelers` | `TravelerRef[]` | yes | Traveler snapshots (structured form, see shared-primitives.md section 2a). |
| `segments` | `SegmentOrderSnapshot[]` | yes | Segment order snapshots. |
| `orderItems` | `OrderItem[]` | yes | Line items. |

**OfferSnapshotRef:**

| Field | Type | Required | Description |
|---|---|---|---|
| `offerId` | `OfferId` | yes | Offer ID. Maps from `OfferQuoted.downstreamReference.offerId`. |
| `offerVersion` | u32 | yes | Offer version at quote time. Maps from `OfferQuoted.downstreamReference.offerVersion`. |
| `priceSnapshotRef` | string | yes | Frozen price snapshot reference. Maps from `OfferQuoted.downstreamReference.priceSnapshotRef`. |
| `ruleSnapshotId` | string | yes | Immutable rule snapshot reference. Maps from `OfferQuoted.downstreamReference.ruleSnapshotRef`. |

**TravelerRef:**

| Field | Type | Required | Description |
|---|---|---|---|
| `travelerId` | `TravelerId` | yes | Traveler ID (`tvl-<uuid>`). |
| `travelerType` | enum | yes | Traveler category (see shared-primitives.md TravelerType enum). Canonical field name; current implementation uses `documentType` as a deviation. |
| `maskedDocumentNo` | string | no | Partially masked document number. |
| `eligibilityRef` | `EligibilityRef` | no | Eligibility determination (see shared-primitives.md section 2b). |

**SegmentOrderSnapshot:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | `SegmentRef` | yes | Segment reference. |
| `offerItemRef` | string | yes | Corresponding offer item ID. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| offer-management | `OfferQuoted`, `OfferExpired` | Offer lifecycle tracking. |
| booking-orchestration | `SegmentBooking` status events | Booking confirmation. |
| payment | `PaymentCaptured` event | Payment recording. |
| entitlement-ticketing | `EntitlementIssued` event | Entitlement summary. |
