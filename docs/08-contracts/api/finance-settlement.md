# Finance & Settlement — HTTP API

Last updated: 2026-07-05

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Finance & Settlement handles revenue recognition, reconciliation cases, and
settlement views. It consumes upstream events and produces financial records.
Commands are event-backed. `GenerateInvoice` is exposed as an HTTP command for
idempotent invoice generation; other financial operations remain bus-only. Query
endpoints are provided for reporting and audit.

## Commands

### Generate Invoice

**POST** `/api/v1/invoices`

Requires `Idempotency-Key` header (UUID v7). Replays with the same key and body
return the original response; reusing a key with a different body returns
`IDEMPOTENCY_KEY_REUSED`.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `orderId` | string | yes | Order whose recognized revenue should be invoiced. |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `invoiceId` | string | Unique invoice ID. |
| `orderId` | string | Invoiced order. |
| `invoiceNumber` | string | Invoice number. |
| `totalAmount` | object | Total amount (Money). |
| `revenueRecognitionIds` | string[] | Included revenue recognitions. |
| `generatedAt` | timestamp | Generation timestamp. |

**Emits:** `InvoiceGenerated`

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

## Query Endpoints

### Get Revenue Recognition

**GET** `/api/v1/revenue-recognitions/{revenueRecognitionId}`

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `revenueRecognitionId` | string | Unique ID. |
| `orderItemId` | string | Order item. |
| `orderId` | string | Parent order. |
| `componentCode` | string | Financial component (`fare`, `tax`, `fee`, `ancillary`, `discount`). |
| `amount` | object | Recognized amount (Money). |
| `recognitionPolicyVersion` | string | Policy version. |
| `recognizedAt` | timestamp | When recognized. |

**Error codes:** `NOT_FOUND`

### Get Reconciliation Case

**GET** `/api/v1/reconciliation-cases/{reconciliationCaseId}`

**Response (200):** Full reconciliation case details.

**Error codes:** `NOT_FOUND`

### List Reconciliation Cases

**GET** `/api/v1/reconciliation-cases?orderId={orderId}&limit=20&offset=0`

**Response (200):** Paginated response.

### Get Invoice

**GET** `/api/v1/invoices/{invoiceId}`

**Response (200):** Invoice details using the same shape as `GenerateInvoice`.

**Error codes:** `NOT_FOUND`

## Bus-only commands

| Command | Trigger | Description |
|---|---|---|
| `RecognizeRevenue` | `PaymentCaptured`, post-sales refund facts | Recognize revenue or refund reductions from event facts. |
| `OpenReconciliationCase` | Mismatch detection | Open a reconciliation case. |
| `ResolveReconciliationCase` | Manual or auto | Resolve a reconciliation case. |
| `RebuildSettlementView` | Manual or scheduled | Rebuild a settlement view from event log. |

## Open Issues

- None.
