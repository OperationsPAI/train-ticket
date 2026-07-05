# Admin & Audit — HTTP API

Last updated: 2026-07-05

## Overview

Admin & Audit provides operator management, manual action requests/approvals,
and audit trail queries. These endpoints are for internal operations use.

## Endpoints

### Register Operator

**POST** `/api/v1/admin/operators`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `email` | string | yes | Operator email address. |
| `role` | enum | yes | `ADMIN`, `OPERATOR`, `SUPER_OPERATOR`, `AUDITOR` |
| `scopes` | string[] | yes | List of permission scopes. |
| `displayName` | string | yes | Operator display name. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `operatorId` | string | Canonical operator ID (`op-<uuid>`). |
| `email` | string | Operator email. |
| `role` | enum | Assigned role. |
| `scopes` | string[] | Permission scopes. |
| `status` | enum | `ACTIVE`, `SUSPENDED` |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`

### Request Manual Action

**POST** `/api/v1/admin/manual-actions`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `targetDomain` | string | yes | Target bounded context (e.g. `journey-order`, `payment`). |
| `targetCommand` | string | yes | Target domain command (e.g. `RequestManualOrderAction`). |
| `businessRef` | string | yes | Business reference (order ID, payment ID). |
| `reasonCode` | string | yes | Reason code (e.g. `CUSTOMER_REQUEST`). |
| `description` | string | yes | Human-readable description. |
| `requestedByOperatorId` | string | yes | Operator requesting the action. |
| `requiresApproval` | boolean | yes | Whether four-eyes approval is needed. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `manualActionId` | string | Canonical manual action ID (`ma-<uuid>`). |
| `targetDomain` | string | Target domain. |
| `status` | enum | `REQUESTED`, `APPROVED`, `REJECTED`, `EXECUTED`, `FAILED` |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`

### Approve Manual Action

**POST** `/api/v1/admin/manual-actions/{manualActionId}/approve`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `approvedByOperatorId` | string | yes | Approving operator ID. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `manualActionId` | string | Action ID. |
| `status` | enum | `APPROVED` |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

### List Audit Trail

**GET** `/api/v1/admin/audit-trail?businessRef={ref}&limit=20&offset=0`

**Query parameters:** `businessRef` (optional), `limit`, `offset`

**Response (200):** Paginated list of audit entries.

### Get Operator

**GET** `/api/v1/admin/operators/{operatorId}`

**Response (200):** Operator details.

**Error codes:** `NOT_FOUND`

## Open Issues

- None.
