# Customer Service — HTTP API

Last updated: 2026-07-05

## Overview

Customer Service manages support cases, evidence attachment, case
classification, and resolution. These endpoints are for customer service
operators and automated support flows.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

## Endpoints

### Open Support Case

**POST** `/api/v1/support-cases`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `requesterRef` | string | yes | Reference to the requester (`tvl-<uuid>` or `usr-<uuid>`). |
| `channel` | enum | yes | `APP`, `WEB`, `PHONE`, `IM`, `EMAIL`, `IN_APP_MESSAGE`, `BOT`, `OPERATOR_CONSOLE` |
| `classification` | string | no | Initial classification code. |
| `priority` | enum | no | `LOW`, `NORMAL`, `HIGH`, `URGENT` |
| `description` | string | yes | Case description from the requester. |
| `businessReferences` | object | no | References to business objects. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Canonical case ID (`sc-<uuid>`). |
| `requesterRef` | string | Requester reference. |
| `channel` | enum | Channel. |
| `priority` | enum | Priority. |
| `status` | enum | `OPENED`, `IN_PROGRESS`, `RESOLVED`, `CLOSED` |
| `createdAt` | timestamp | Creation time. |

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`

### Get Support Case

**GET** `/api/v1/support-cases/{caseId}`

**Response (200):** Full case details.

**Error codes:** `NOT_FOUND`

### Attach Evidence

**POST** `/api/v1/support-cases/{caseId}/evidence`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceType` | enum | yes | `SCREENSHOT`, `CALL_RECORDING`, `CHAT_TRANSCRIPT`, `EMAIL`, `CHANNEL_RECEIPT`, `PROVIDER_SUMMARY`, `DOCUMENT`, `OTHER` |
| `reference` | string | yes | External reference URI or path. |
| `summary` | string | yes | Human-readable summary. |
| `accessLevel` | enum | yes | `PUBLIC`, `INTERNAL`, `SENSITIVE`, `RESTRICTED` |
| `attachedBy` | string | yes | Operator reference (`op-<uuid>`). |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `evidenceId` | string | Evidence ID (`evid-<uuid>`). |
| `caseId` | string | Case ID. |
| `evidenceType` | enum | Type. |

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

### Classify Support Case

**POST** `/api/v1/support-cases/{caseId}/classify`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `classification` | string | yes | Classification code. |
| `priority` | enum | yes | `LOW`, `NORMAL`, `HIGH`, `URGENT` |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

### Assign Support Case

**POST** `/api/v1/support-cases/{caseId}/assign`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `assignedTo` | string | no | Operator ID to assign (`op-<uuid>`). |
| `ownerQueue` | string | yes | Target queue name. |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

### Escalate Case

**POST** `/api/v1/support-cases/{caseId}/escalate`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `targetQueue` | string | yes | Target escalation queue. |
| `reason` | string | yes | Escalation reason. |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

### Resolve Case

**POST** `/api/v1/support-cases/{caseId}/resolve`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `summary` | string | yes | Resolution summary. |
| `resolutionCode` | string | yes | Resolution code. |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

### Close Case

**POST** `/api/v1/support-cases/{caseId}/close`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | enum | yes | `RESOLVED`, `ESCALATED`, `DUPLICATE`, `NO_FURTHER_ACTION`, `CUSTOMER_CLOSED` |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`

### Reopen Case

**POST** `/api/v1/support-cases/{caseId}/reopen`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `reason` | string | yes | Reason for reopening. |
| `requesterRef` | string | yes | Reference to the requester. |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`

## Bus-only commands

The following commands are consumed from the event bus only and have no HTTP
endpoint:

| Command | Trigger | Description |
|---|---|---|
| `RequestManualAction` | Customer Service UI | Request a manual action against a target domain (e.g. payment, order). |
| `RecordActionOutcome` | Customer Service UI / Admin & Audit | Record the outcome of a manual action. |
| `AppendTimelineEntry` | Customer Service UI / Internal | Append an event to the case timeline. |

## Open Issues

- None.
