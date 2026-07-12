# Supplier Catalog — HTTP API

Last updated: 2026-07-05

## Overview

Supplier Catalog manages suppliers, carriers, and commercial contracts. It is
the master data source for provider integration and fare pricing.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

## Endpoints

### Register Supplier

**POST** `/api/v1/suppliers`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `legalName` | string | yes | Legal entity name. |
| `brandName` | string | yes | Brand or trading name. |
| `supplierCode` | string | yes | Short supplier code. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `supplierId` | string | Canonical supplier ID (`sup-<uuid>`). |
| `legalName` | string | Legal name. |
| `brandName` | string | Brand name. |
| `status` | enum | `DRAFT`, `UNDER_REVIEW`, `ACTIVE`, `SUSPENDED`, `INACTIVE`, `REJECTED`, `ARCHIVED` |
| `registeredAt` | timestamp | Registration time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`

### Get Supplier

**GET** `/api/v1/suppliers/{supplierId}`

**Response (200):** Full supplier details.

**Error codes:** `NOT_FOUND`

### List Suppliers

**GET** `/api/v1/suppliers?status=ACTIVE&limit=20&offset=0`

**Response (200):** Paginated response.

### Register Carrier

**POST** `/api/v1/carriers`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `supplierId` | string | yes | Parent supplier ID (`sup-<uuid>`). |
| `name` | string | yes | Carrier display name. |
| `code` | string | yes | Carrier code (e.g. `BJRAIL`, `CA`). |
| `transportMode` | string | yes | `RAIL`, `AIR`, `COACH`, `FERRY`, `RIDE_HAILING` |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `carrierId` | string | Canonical carrier ID (`car-<uuid>`). |
| `supplierId` | string | Parent supplier. |
| `name` | string | Carrier name. |
| `code` | string | Carrier code. |
| `transportMode` | string | Transport mode. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`

### Activate Contract

**POST** `/api/v1/contracts`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `supplierId` | string | yes | Supplier ID. |
| `carrierId` | string | yes | Carrier ID. |
| `contractRef` | string | yes | External contract reference. |
| `effectiveFrom` | timestamp | yes | Contract effective date. |
| `effectiveUntil` | timestamp | no | Contract expiry date. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `contractId` | string | Contract ID. |
| `status` | enum | `DRAFT`, `PUBLISHED`, `SUSPENDED`, `TERMINATED` |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `SuspendContract` | Admin UI / Automated policy | Suspend an active contract. |
| `DeclareProductCapability` | Admin UI / Capability discovery | Declare a product capability for a supplier contract. |
| `MapExternalCode` | Admin UI / Import batch / ACL | Map an external supplier code to an internal platform reference. |

## Open Issues

- None.
