# Finance & Settlement — HTTP API

Last updated: 2026-07-05

## Overview

Field shapes reference docs/08-contracts/shared-primitives.md for IDs,
timestamps, and Money.

Finance & Settlement handles revenue recognition, reconciliation cases, and
settlement views. It consumes upstream events and produces financial records.
ADR-0003 Wave A same-wave increment（需同波实现）: Finance Settlement consumes Payment Channel
`ChannelStatementGenerated`, `ChannelStatementFrozen`, and discrepancy events for
daily SIM channel reconciliation. This requires same-wave code touchpoints in
event consumer registration, statement payload validation, consumed-event dedup,
and mapping Payment Channel SCREAMING_SNAKE `differenceType` values to the
existing Finance difference strings.
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


### List Benefit Costs

**GET** `/api/v1/benefit-costs?accountId={accountId}&limit=20&offset=0`

Internal operations read endpoint for SYSTEM/OPS benefit cost attribution from
Wallet / Promotion events. `accountId` is optional; when omitted the endpoint
returns all cost entries in reverse occurrence order.

**Response (200):** Paginated response with `items`, `total`, `limit`, and `offset`.

Item fields:

| Field | Type | Description |
|---|---|---|
| `benefitId` | string | Wallet benefit ID. |
| `accountId` | string | Benefit owner / cost attribution account. |
| `issuanceSource` | enum | `MANUAL_OPS`, `POST_SALES_COMP`, `DISRUPTION_COMP`, or `UNKNOWN` when no prior issuance attribution is available for a lifecycle fact. |
| `caseId` | string | Optional post-sales/disruption case reference; lifecycle events inherit the latest known benefit attribution when the event payload does not carry it. |
| `amount` | Money | Signed cost delta in Money minorUnits. Issuance/redemption are positive; reversal/revocation/expiry are negative reductions. |
| `eventType` | enum | `BenefitIssued`, `BenefitRedeemed`, `BenefitReversed`, `BenefitRevoked`, or `BenefitExpired`. |
| `occurredAt` | RFC3339 UTC | Event payload timestamp parsed from wallet RFC3339 fields. |

**Error codes:** `VALIDATION_FAILED`

### Get Invoice

**GET** `/api/v1/invoices/{invoiceId}`

**Response (200):** Invoice details using the same shape as `GenerateInvoice`.

**Error codes:** `NOT_FOUND`

## Bus-only commands

| Command | Trigger | Description |
|---|---|---|
| `RecognizeRevenue` | `PaymentCaptured`, post-sales refund facts | Recognize revenue from capture facts and reverse previously recognized revenue from post-sales refund facts. |
| `OpenReconciliationCase` | Mismatch detection, including Payment Channel statement discrepancies | Open a reconciliation case. ADR-0003 same-wave implementation validates channel statement refs and maps Payment Channel difference types without adding a new Finance enum. |
| `ResolveReconciliationCase` | Manual or auto | Resolve a reconciliation case. |
| `RebuildSettlementView` | Manual or scheduled | Rebuild a settlement view from event log. |

## Open Issues

- None.
