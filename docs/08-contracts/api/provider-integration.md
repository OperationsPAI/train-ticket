# Provider Integration — HTTP API

Last updated: 2026-07-05

## Overview

Provider Integration manages external provider (supplier) communication. It
translates internal commands to provider-specific protocols, handles
idempotency, and normalizes responses. Endpoints in this section are
**internal** — called by Booking Orchestration, not end-user clients.

## Internal Endpoints

### Request Provider Reservation

**POST** `/api/v1/internal/provider-reservations`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | string | yes | Platform segment booking ID (`sb-<uuid>`). |
| `providerConfigRef` | string | yes | Provider configuration reference. |
| `reservationPayload` | object | yes | Provider-specific reservation payload. |
| `idempotencyKey` | string | yes | Idempotency key for safe retry. |

**Response (202):**

| Field | Type | Description |
|---|---|---|
| `segmentBookingId` | string | Segment booking ID. |
| `status` | enum | `PENDING`, `CONFIRMED`, `FAILED`, `TIMED_OUT` |
| `providerReference` | string | Provider's confirmation reference (if confirmed). |

**Error codes:** `VALIDATION_FAILED`, `UNAVAILABLE`

### Cancel Provider Reservation

**POST** `/api/v1/internal/provider-reservations/{segmentBookingId}/cancel`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `segmentBookingId` | string | Segment booking ID. |
| `cancellationStatus` | enum | `CANCELLED`, `PENDING`, `FAILED` |

**Error codes:** `NOT_FOUND`, `UNAVAILABLE`

## Bus-only commands

- `ProviderReservationConfirmed` (published event, not a command)
- `ProviderReservationFailed` (published event, not a command)

## Open Issues

- None.
