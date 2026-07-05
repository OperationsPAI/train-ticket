# Finance & Settlement — HTTP API

Last updated: 2026-07-05

## Overview

Finance & Settlement handles revenue recognition, reconciliation cases, and
settlement views. It consumes upstream events and produces financial records.
All commands are **bus-only** — there are no external HTTP endpoints for
financial operations. Query endpoints are provided for reporting and audit.

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

## Bus-only commands

| Command | Trigger | Description |
|---|---|---|
| `RecognizeRevenue` | `EntitlementIssued`, `BoardingVerified` | Recognize revenue from fulfillment events. |
| `OpenReconciliationCase` | Mismatch detection | Open a reconciliation case. |
| `ResolveReconciliationCase` | Manual or auto | Resolve a reconciliation case. |
| `RebuildSettlementView` | Manual or scheduled | Rebuild a settlement view from event log. |

## Open Issues

- None.
