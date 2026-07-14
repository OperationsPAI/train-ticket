# Provider Integration — Events & Commands

Last updated: 2026-06-28

## Published Events

### ProviderReservationConfirmed

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | booking-orchestration |
| **Trigger** | External provider confirmed the reservation. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Platform segment booking ID. |
| `providerReference` | `ProviderReference` | yes | Provider's confirmation reference. |
| `normalizedEvidence` | string | yes | Normalised confirmation evidence. |

### ProviderReservationFailed

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | booking-orchestration |
| **Trigger** | External provider rejected or failed the reservation. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Platform segment booking ID. |
| `errorType` | enum | yes | `BUSINESS_REJECTED`, `RETRYABLE_TECHNICAL_ERROR`, `NON_RETRYABLE_TECHNICAL_ERROR`, `AMBIGUOUS_RESULT`. |
| `errorMessage` | string | no | Human-readable error for audit. |

### ProviderReservationTimedOut

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | booking-orchestration |
| **Trigger** | Provider reservation request timed out. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID. |
| `reason` | string | yes | Timeout reason. |

### ProviderTicketIssued

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | entitlement-ticketing |
| **Trigger** | External provider issued a ticket/credential. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entitlementId` | `EntitlementId` | yes | Platform entitlement ID. |
| `providerReference` | `ProviderReference` | yes | Provider's ticket reference. |

### SegmentDelayed

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | disruption-recovery |
| **Trigger** | External provider reports an operational delay for a booked service segment. |

**Payload:** same shape as Fulfillment `SegmentDelayed`: `segmentRef`,
`scheduledServiceRef`, `serviceDate`, `estimatedArrivalAt`, `observedAt`, and
`sourceSystem`. Disruption Recovery maps this event to
`disruptionType=DELAY` with `evidence.sourceSystem=PROVIDER_INTEGRATION`.

### SegmentCancelled

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | disruption-recovery |
| **Trigger** | External provider reports cancellation for a booked service segment. |

**Payload:** same shape as Fulfillment `SegmentCancelled`: `segmentRef`,
`scheduledServiceRef`, `serviceDate`, `cancelledAt`, `observedAt`, and
`sourceSystem`. Disruption Recovery maps this event to
`disruptionType=CANCELLATION` with `evidence.sourceSystem=PROVIDER_INTEGRATION`.
