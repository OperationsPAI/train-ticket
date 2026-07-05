# Account — HTTP API

Last updated: 2026-07-05

## Overview

Account manages user accounts, sessions, and account lifecycle (freeze,
unfreeze, closure). It is the identity and authentication foundation.

## Endpoints

### Create Account

**POST** `/api/v1/accounts`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | no | Optional client-generated account ID. Generated if absent. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `accountId` | string | Canonical account ID (`acct_<uuid>`). |
| `status` | enum | `ACTIVE`, `FROZEN`, `CLOSED` |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`

### Get Account

**GET** `/api/v1/accounts/{accountId}`

**Response (200):** Full account details.

**Error codes:** `NOT_FOUND`

### Freeze Account

**POST** `/api/v1/accounts/{accountId}/freeze`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Reason for freezing. |
| `operator` | string | yes | Who initiated the freeze. |
| `caseRef` | string | no | Customer service case reference. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `accountId` | string | Account ID. |
| `status` | enum | `FROZEN` |
| `frozenAt` | timestamp | When freeze was applied. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`

### Unfreeze Account

**POST** `/api/v1/accounts/{accountId}/unfreeze`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Reason for unfreezing. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `accountId` | string | Account ID. |
| `status` | enum | `ACTIVE` |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

### Update Preference

**PATCH** `/api/v1/accounts/{accountId}/preferences`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `preferenceKey` | string | yes | Preference key. |
| `value` | string | yes | Preference value. |

**Response (200):** Updated account details.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

### Start Account Closure

**POST** `/api/v1/accounts/{accountId}/start-closure`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `accountId` | string | Account ID. |
| `closureRequestId` | string | Closure request ID. |
| `status` | enum | `CLOSURE_INITIATED` |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

## Bus-only commands

- `OpenSession` (bus-only: triggered by auth gateway)
- `RevokeSession` (bus-only: triggered by auth gateway)
- `CompleteAccountClosure` (bus-only: saga internal)

## Open Issues

- None.
