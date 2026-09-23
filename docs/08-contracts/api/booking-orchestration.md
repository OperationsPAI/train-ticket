# Booking Orchestration — HTTP API

Last updated: 2026-09-23

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Booking Orchestration manages the booking saga for a journey order. It
coordinates segment reservations, capacity holds, payment, and ticketing
steps. Endpoints in this section are **internal** — they are called by other
services (not end-user clients).

## Internal Endpoints

### Cancel a replacement during capacity compensation

`POST /api/v1/internal/change-compensations/{bookingId}` accepts `caseId` and requires `X-Correlation-Id`.
The service reads the case and requires an executing change with persisted `capacity-rejected` evidence and a recorded reference to the replacement booking.
Any recorded original ticket void or applied order change prevents this operation.
The replacement must belong to the case and order.
An issued replacement requires its ticket void confirmation before direct reservation cancellation.
Successful cancellation persists `CHANGE_CAPACITY_UNAVAILABLE` and publishes one `SegmentBookingCancelled` event.
Repeated cancellation returns the terminal booking without another event.
Post Sales separately verifies Hold release, original ticket preservation and difference payment settlement before completing compensation.

### Reserve a Change Replacement

`POST /api/v1/internal/change-reservations` accepts `caseId`, `segmentRef` and `travelerRef`, with `Idempotency-Key` and `X-Correlation-Id` headers.
The approved change case supplies the selected offer and original order.
The original order must have risk clearance and be confirmed.
The selected offer supplies the permitted travelers, segments and exact fare quote, including the requested seat class.
The itinerary supplies `scheduledServiceRef` and `serviceDate`.
The accepted booking persists those fields together with `classRef` and `offerItemRef`.
Subsequent order changes do not replace the booking's supply reference.
Each case, segment and traveler has one replacement booking under an independent change saga.
Repeated requests return the existing booking and reject changes to its offer item or class.
The response uses the segment booking details shape.

### Accepted reservation facts

The supply reference implementation requires a coordinated Booking and Capacity rollout.
Source tests verify it, but deployment status must be checked separately.
Initial reservation commands resolve the parent order's accepted Offer, fare quote and itinerary.
Account, traveler set, segment, departure time and endpoints must agree before a booking is persisted.
The resulting `SegmentReservationRequested` includes `classRef`, `offerItemRef`, `scheduledServiceRef` and `serviceDate`.
Capacity uses these references to select the physical supply and quota policy.

`GET /api/v1/internal/segment-bookings/{segmentBookingId}` exposes the persisted supply fields.
Historical records may lack fields added by this integration and require evidence based reconciliation before supply activation.
The business key is the order, segment, traveler and booking purpose.
Concurrent requests for the same business key reuse the existing booking when commercial facts agree.
Callers must use the returned `segmentBookingId`; a proposed identifier need not become the stored identifier.
Database snapshot, outbox and uniqueness verification is covered by `ReservationFactsLiveTest`.

### Start Booking Saga

**POST** `/api/v1/internal/booking-sagas`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | string | yes | Parent order ID (`ord-<uuid>`). |
| `accountId` | string | yes | Owning account. |
| `offerId` | string | yes | Source offer ID. |
| `travelerRefs` | array | yes | Traveler references. |
| `segmentRefs` | string[] | yes | Segment references. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `sagaId` | string | Canonical saga ID (`saga-<uuid>`). |
| `journeyOrderId` | string | Parent order ID. |
| `status` | enum | `STARTED`, `RESERVING`, `HELD`, `WAITING_PAYMENT`, `TICKETING`, `COMPLETED`, `FAILED` |
| `startedAt` | timestamp | Saga start time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `PRECONDITION_FAILED`

### Get Saga Status

**GET** `/api/v1/internal/booking-sagas/{sagaId}`

**Response (200):** Full saga details including step statuses.

**Error codes:** `NOT_FOUND`

### Request Segment Reservation

**POST** `/api/v1/internal/booking-sagas/{sagaId}/request-reservation`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | string | yes | Service segment reference. |
| `travelerRef` | string | yes | Traveler reference. |
| `segmentBookingId` | string | yes | Segment booking ID (`sb-<uuid>`). |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `segmentBookingId` | string | Segment booking ID. |
| `status` | enum | `REQUESTED`, `CONFIRMED`, `FAILED` |

**Error codes:** `NOT_FOUND`, `CONFLICT`, `UNAVAILABLE`

### Get Segment Booking

**GET** `/api/v1/internal/segment-bookings/{segmentBookingId}`

Returns `segmentBookingId`, `sagaId`, `journeyOrderId`, `segmentRef`, `travelerRef`, `status`, `capacityHoldId`, `entitlementId`, and `failureReason`.
The status describes the persisted segment reservation.
The capacity hold reference permits verification of capacity confirmation before ticket issuance.
An unknown segment booking returns `NOT_FOUND`.

### Mark Segment Ticketed

**POST** `/api/v1/internal/booking-sagas/{sagaId}/mark-ticketed`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | string | yes | Segment booking ID. |
| `entitlementId` | string | yes | Issued entitlement ID. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `segmentBookingId` | string | Segment booking ID. |
| `status` | enum | `TICKETED` |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

## Bus-only commands

- `ConfirmSegmentReservation` (internal: triggered by capacity/provider events)
- `CancelSegmentBooking` (internal: triggered by post-sales saga)

## Open Issues

- None.
