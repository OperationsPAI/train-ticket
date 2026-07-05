# Booking Orchestration — HTTP API

Last updated: 2026-07-05

## Overview

Booking Orchestration manages the booking saga for a journey order. It
coordinates segment reservations, capacity holds, payment, and ticketing
steps. Endpoints in this section are **internal** — they are called by other
services (not end-user clients).

## Internal Endpoints

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
