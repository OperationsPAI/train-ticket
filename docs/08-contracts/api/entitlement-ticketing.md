# Entitlement & Ticketing — HTTP API

Last updated: 2026-07-05

## Overview

Entitlement & Ticketing manages ticket/credential issuance, voiding, and
status queries. It is the authoritative source for ticket credentials.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

## Endpoints

### Issue Entitlement

**POST** `/api/v1/entitlements`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentBookingId` | string | yes | Reference to the segment booking (`sb-<uuid>`). |
| `journeyOrderId` | string | yes | Reference to the journey order (`ord-<uuid>`). |
| `travelerRef` | string | yes | Traveler reference (`tvl-<uuid>`). |
| `segmentRef` | string | yes | Segment reference (`seg-<uuid>`). |
| `issuePurpose` | string | yes | Purpose: `INITIAL`, `REPLACEMENT`, `MANUAL_RECOVERY`, `PROVIDER_REBUILD`, `DISRUPTION_REPLACEMENT` |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `entitlementId` | string | Canonical entitlement ID (`ent-<uuid>`). |
| `segmentBookingId` | string | Segment booking ID. |
| `journeyOrderId` | string | Order ID. |
| `credentialNo` | string | Unique credential/ticket number. |
| `credentialType` | string | Type: `E_TICKET`, `PAPER_TICKET`, `PICKUP_CODE`, `BOARDING_PASS`, `FERRY_TICKET`, `COACH_E_TICKET`, `RIDE_CODE` |
| `status` | enum | `ISSUED`, `VOIDED`, `BOARDED`, `NO_SHOW`, `SUSPENDED`. RULING (2026-07-06): there is no `USED` status — `FulfillmentCompleted` leaves the ticket `BOARDED`; `BOARDED`, `NO_SHOW`, and `VOIDED` are terminal. |
| `issuedAt` | timestamp | Issue timestamp. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`

### Void Entitlement

**POST** `/api/v1/entitlements/{entitlementId}/void`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | `REFUND`, `CHANGE`, `DISRUPTION`, `RISK`, `MANUAL_CORRECTION` |
| `policy` | string | yes | `NORMAL`, `EXCEPTIONAL_RULE` |
| `businessCaseRef` | string | no | Reference to the post-sales or recovery case. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `entitlementId` | string | Entitlement ID. |
| `status` | enum | `VOIDED` |
| `voidedAt` | timestamp | Void timestamp. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`

### Get Entitlement

**GET** `/api/v1/entitlements/{entitlementId}`

**Response (200):** Full entitlement details.

**Error codes:** `NOT_FOUND`

### List Entitlements by Order

**GET** `/api/v1/entitlements?journeyOrderId={orderId}&limit=20&offset=0`

**Query parameters:** `journeyOrderId` (required), `limit`, `offset`

**Response (200):** Paginated response.

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `SuspendEntitlement` | risk-compliance, post-sales, disruption-recovery | Suspend an entitlement due to risk, dispute, or provider conflict. |
| `ResumeEntitlement` | risk-compliance, post-sales, customer-service | Resume a suspended entitlement. |
| `BindProviderCredential` | provider-integration | Bind a provider-issued credential to an entitlement. |

## Open Issues

- None.
