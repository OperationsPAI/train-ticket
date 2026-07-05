# Customer Service — HTTP API

Last updated: 2026-07-05

## Overview

Customer Service manages support cases, evidence attachment, case
classification, and resolution. These endpoints are for customer service
operators and automated support flows.

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

### Update Case Status

**PATCH** `/api/v1/support-cases/{caseId}/status`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `status` | enum | yes | `IN_PROGRESS`, `RESOLVED`, `CLOSED` |
| `resolution` | string | no | Resolution description. |

**Response (200):** Updated case.

**Error codes:** `NOT_FOUND`, `VALIDATION_FAILED`, `PRECONDITION_FAILED`

## Open Issues

- None.
