# Entitlement and Ticketing HTTP API

Last updated: 2026-09-23.

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
| `seatPreferences` | object | no | Seat Assignment preferences, including `acceptStanding`, adjacency, seat and berth positions, compartment, exclusions and preference version. |
| `scheduledServiceRef`, `serviceDate` | string | for train issuance | Accepted scheduled service and calendar date. |
| `capacityHoldId`, `capacityUnitRef` | string | for train issuance | Confirmed capacity Hold and its inventory reference. |
| `interval` | object | for train issuance | Accepted station interval with `fromSeq` and `toSeq`. |
| `classRef` | string | for train issuance | Accepted seat, berth or standing class. |
| `expiresAt` | timestamp | for train issuance | Allocation validity boundary. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `entitlementId` | string | Canonical entitlement ID (`ent-<uuid>`). |
| `segmentBookingId` | string | Segment booking ID. |
| `journeyOrderId` | string | Order ID. |
| `credentialNo` | string | Unique credential/ticket number. |
| `credentialType` | string | Type: `E_TICKET`, `PAPER_TICKET`, `PICKUP_CODE`, `BOARDING_PASS`, `FERRY_TICKET`, `COACH_E_TICKET`, `RIDE_CODE` |
| `status` | enum | `ISSUED`, `VOIDED`, `BOARDED`, `NO_SHOW`, `SUSPENDED`; completed fulfillment retains `BOARDED`. |
| `issuedAt` | timestamp | Issue timestamp. |
| `seatRef` | object | Seat Assignment display fact, required for train issuance and including successful `STANDING` allocation. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`, `DOMAIN_RULE_VIOLATION`, `UNAVAILABLE`

A valid Seat Assignment business error preserves its HTTP status, code and message through ticket issuance.
For example, exhausted concrete capacity without accepted standing returns HTTP 422 with `DOMAIN_RULE_VIOLATION`.
Transport failures, malformed error responses and unrecognized downstream status and code combinations return `UNAVAILABLE`.
Seat allocation rejection does not issue a ticket or commit its issuance result.

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


### Seat Assignment integration

Before committing a train credential, issuance calls Seat Assignment's `POST /api/v1/internal/seat-allocations`.
The outbound UUID version 7 idempotency key is derived from `segmentBookingId`, `travelerRef`, `capacityHoldId`, normalized `seatPreferences` and `issuePurpose`.
The ticket persists and exposes the returned `seatRef`.
Standing is a valid allocation with no physical SeatUnit or seat number, governed by the [standing capacity policy](../standing-capacity-policy.md).
The six class live purchase test checks the issued allocation against the purchased class.

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `SuspendEntitlement` | risk-compliance, post-sales, disruption-recovery | Suspend an entitlement due to risk, dispute, or provider conflict. |
| `ResumeEntitlement` | risk-compliance, post-sales, customer-service | Resume a suspended entitlement. |
| `BindProviderCredential` | provider-integration | Bind a provider-issued credential to an entitlement. |

## Integration status

Preserving Seat Assignment business errors allows callers to distinguish sold out inventory from a dependency failure.
Post Sales must still complete replacement cancellation and any required payment compensation before returning a terminal change result.
See [capacity integration requirements](../../10-domain-enrichment/capacity-overbooking.md) for the coordinated activation requirements.
