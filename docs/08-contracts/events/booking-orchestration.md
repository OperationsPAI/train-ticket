# Booking Orchestration — Events & Commands

Last updated: 2026-06-28

## Published Events

### BookingSagaStarted

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | journey-order |
| **Trigger** | `StartBookingSaga` command received after `JourneyOrderCreated`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `sagaId` | `SagaId` | yes | Canonical saga ID (`saga-<uuid>`). |
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order ID. |
| `startedAt` | RFC3339 UTC | yes | Saga start time. |

### SegmentReservationRequested

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | capacity-availability, provider-integration |
| **Trigger** | `RequestSegmentReservation` command executed for a segment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID (`sb-<uuid>`). |
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order ID. |
| `segmentRef` | `SegmentRef` | yes | Service segment reference. |
| `travelerRef` | `TravelerId` | yes | Traveler reference. |
| `idempotencyKey` | string | yes | Idempotency key for safe retry. |

### SegmentCapacityHolding

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | capacity-availability |
| **Trigger** | Capacity hold initiated for a segment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID. |
| `capacityHoldId` | `HoldId` | yes | Reference to the capacity hold. |

### SegmentReservationConfirmed

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | journey-order |
| **Trigger** | Internal or provider reservation confirmed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID. |
| `providerReference` | `ProviderReference` | no | Provider reference if external. |
| `evidence` | string | yes | Confirmation evidence. |

### SegmentReservationFailed

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | journey-order |
| **Trigger** | Reservation failed (internal or provider). |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID. |
| `reason` | string | yes | Failure reason. |

### SegmentBookingCancelled

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | capacity-availability, provider-integration |
| **Trigger** | Segment booking cancelled. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID. |
| `reason` | string | yes | Cancellation reason. |

### SegmentTicketed

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | journey-order |
| **Trigger** | Entitlement issued for this segment booking. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | `SegmentBookingId` | yes | Segment booking ID. |
| `entitlementId` | `EntitlementId` | yes | Reference to the issued entitlement. |

### BookingSagaCompleted

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | journey-order |
| **Trigger** | All saga steps completed successfully. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `sagaId` | `SagaId` | yes | Saga ID. |
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order ID. |

### BookingSagaFailed

| Field | Description |
|---|---|
| **Producer** | booking-orchestration |
| **Consumers** | journey-order |
| **Trigger** | Saga reached terminal failure. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `sagaId` | `SagaId` | yes | Saga ID. |
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order ID. |
| `reason` | string | yes | Failure reason. |

## Accepted Commands

### StartBookingSaga

| Field | Description |
|---|---|
| **Sender** | journey-order (via internal event handler) |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order. |
| `orderVersion` | string | yes | Order version for idempotency. |
| `bookingPurpose` | string | yes | Purpose (e.g. `purchase`). |
| `plan` | `BookingSagaStep[]` | yes | Ordered list of saga steps. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| journey-order | `JourneyOrderCreated` | Saga trigger. |
| capacity-availability | `CapacityHeld`, `CapacityHoldConfirmed` | Hold lifecycle. |
| provider-integration | `ProviderReservationConfirmed` | External provider confirmation. |
| payment | `PaymentCaptured` | Payment condition for ticketing. |
| entitlement-ticketing | `EntitlementIssued` | Ticketing completion. |
