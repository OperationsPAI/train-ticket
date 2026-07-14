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


### ProviderSegmentDelayed

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | disruption-recovery |
| **Trigger** | External provider reports a delay for a booked operating segment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | `SegmentRef` | yes | Affected service segment (`seg-<uuid>`). |
| `scheduledServiceRef` | string | yes | Scheduled service/run reference. |
| `serviceDate` | string | yes | Operating date, `YYYY-MM-DD`. |
| `estimatedArrivalAt` | RFC3339 UTC | yes | Latest estimated arrival. |
| `observedAt` | RFC3339 UTC | yes | When the provider delay status was observed. |
| `sourceSystem` | string | yes | Provider declaration source, normally `PROVIDER`. |

### ProviderSegmentCancelled

| Field | Description |
|---|---|
| **Producer** | provider-integration |
| **Consumers** | disruption-recovery |
| **Trigger** | External provider reports cancellation for a booked operating segment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | `SegmentRef` | yes | Affected service segment (`seg-<uuid>`). |
| `scheduledServiceRef` | string | yes | Scheduled service/run reference. |
| `serviceDate` | string | yes | Operating date, `YYYY-MM-DD`. |
| `cancelledAt` | RFC3339 UTC | yes | Cancellation timestamp. |
| `observedAt` | RFC3339 UTC | yes | When the provider cancellation status was observed. |
| `sourceSystem` | string | yes | Provider declaration source, normally `PROVIDER`. |

## Accepted Commands

### ReportProviderSegmentStatus

| Field | Description |
|---|---|
| **Sender** | provider webhook adapter / operations provider simulator |
| **Trigger** | External provider reports a delay or cancellation for a booked operating segment. |
| **HTTP ingress** | `POST /api/v1/provider-segment-status` with `Idempotency-Key` UUID-v7 header. |
| **Produced events** | `ProviderSegmentDelayed` when `status=DELAY`; `ProviderSegmentCancelled` when `status=CANCELLED`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | `SegmentRef` | yes | Affected service segment (`seg-<uuid>`). |
| `scheduledServiceRef` | string | yes | Scheduled service/run reference. |
| `serviceDate` | string | yes | Operating date, `YYYY-MM-DD`. |
| `status` | enum | yes | `DELAY` or `CANCELLED`. |
| `estimatedArrivalAt` | RFC3339 UTC | yes when `status=DELAY` | Latest estimated arrival. |
| `cancelledAt` | RFC3339 UTC | yes when `status=CANCELLED` | Cancellation timestamp. |
| `observedAt` | RFC3339 UTC | yes | When the provider status was observed. |
| `sourceSystem` | string | no | Provider declaration source; defaults to `PROVIDER`. |
