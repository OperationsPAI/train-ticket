# Traveler Profile — HTTP API

Last updated: 2026-07-05

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Traveler Profile manages traveler identities, travel documents, contact
information, and eligibility determinations (student, senior, military
discounts).

## Endpoints

### Create Traveler Profile

**POST** `/api/v1/travelers`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Owning account. |
| `travelerType` | enum | yes | `ADULT`, `CHILD`, `INFANT`, `STUDENT`, `SENIOR`, `MILITARY` |
| `givenName` | string | yes | Given name. |
| `familyName` | string | yes | Family name. |
| `documentType` | enum | no | `ID_CARD`, `PASSPORT`, `OTHER` |
| `documentNumber` | string | no | Travel document number (will be masked in snapshots). |
| `contactEmail` | string | no | Contact email. |
| `contactPhone` | string | no | Contact phone number. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `travelerId` | string | Canonical traveler ID (`tvl-<uuid>`). |
| `snapshotVersion` | string | Initial snapshot version. |
| `travelerType` | enum | Traveler type. |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`

### Get Traveler Profile

**GET** `/api/v1/travelers/{travelerId}`

**Response (200):** Full traveler profile (document numbers masked).

**Error codes:** `NOT_FOUND`

### Update Traveler Profile

**PATCH** `/api/v1/travelers/{travelerId}`

**Idempotency:** REQUIRED

**Request:** Partial fields from the create request.

**Response (200):** Updated traveler profile.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

### Determine Eligibility

**POST** `/api/v1/travelers/{travelerId}/eligibility`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `travelerId` | string | Traveler ID. |
| `eligibilityRef` | string | Eligibility reference. |
| `eligible` | boolean | Whether the traveler is eligible. |
| `validFrom` | timestamp | Eligibility validity start. |
| `validUntil` | timestamp | Eligibility validity end. |

**Error codes:** `NOT_FOUND`, `UNAVAILABLE`

## Open Issues

- None.
