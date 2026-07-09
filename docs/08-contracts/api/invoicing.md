# Invoicing — HTTP API

Last updated: 2026-07-10

## Overview

Invoicing owns invoice titles, electronic blue-invoice issuance, red-flush audit
state, and read-only itinerary-receipt generation. This contract is scoped to
ADR-0003 Wave A and is bounded by `docs/02-domains/invoicing.md`.

Wave-A rulings:

- Invoice title CRUD is active. Deletion is a soft deactivation; title versions
  already used for invoice requests are immutable.
- Blue e-invoice issuance uses the SIM tax-bureau gateway only. The SIM gateway
  never calls a real external network and its acceptance/rejection behavior is
  deterministic from the normalized request material plus optional `simSeedRef`.
- Red-flush enforcement is observational in this wave. Invoicing consumes the
  existing Post Sales true applied-refund fact, `PostSalesApplied`, and records a
  violation when funds were refunded while an issued blue invoice has no completed
  red flush. **RULING:** Post Sales does not add a pre-refund blocking hook in
  this wave; tightening that hook is future work.
- Itinerary receipt generation is exposed as a read-only GET projection. It does
  not create a user command, does not mutate an aggregate, and does not replace a
  tax e-invoice.
- Reimbursement-export write APIs are deferred.

Field shapes reference `docs/08-contracts/shared-primitives.md` and
`docs/08-contracts/api/README.md`. Money is always `{currency, minorUnits}`;
timestamps are RFC3339 UTC; JSON fields are camelCase; enum values are
SCREAMING_SNAKE_CASE.

## Wire identity, correlation, and idempotency

State-changing endpoints require `Idempotency-Key` and `X-Correlation-Id` as in
`api/README.md`, with these Invoicing-specific constraints:

| Wire value | Format | Rule |
|---|---|---|
| `Idempotency-Key` header | UUID-v7 shape | API gateway passes this value through unchanged as the external command idempotency key. |
| `sourceCommandId` | `cmd-<uuid-v7>` | Generated from the command identity. If the caller only supplies the header UUID, the service folds it into `cmd-<uuid-v7>` for envelopes and audit timelines. |
| `correlationId` | `corr-<uuid-v7>` | If the incoming `X-Correlation-Id` lacks the `corr-` prefix, the edge/service folds the UUID into `corr-<uuid-v7>` on published envelopes and returns the canonical value in response headers. |
| Internal idempotency key | material hash | Domain duplicate detection is based on the normalized business material tables below, not on the header alone. |

Replays with the same `Idempotency-Key` and identical normalized body return the
original response. Reusing the same key with different normalized material
returns `IDEMPOTENCY_KEY_REUSED` (422). A semantically duplicate command with a
new header key returns the existing resource when the material-folding table says
it is the same command, unless the current aggregate state makes the request a
`CONFLICT` or `PRECONDITION_FAILED`.

### Command material folding

Normalization: trim strings, collapse internal whitespace where noted, uppercase
SCREAMING_SNAKE enums and ISO currency, sort arrays lexicographically, encode
Money as `currency + ':' + minorUnits`, encode timestamps as RFC3339 UTC, omit
absent optional fields, and hash the canonical JSON with SHA-256.

| Command / endpoint | API header key use | Internal folded material |
|---|---|---|
| `CreateInvoiceTitle` / `POST /api/v1/invoice-titles` | Header UUID-v7 is stored as external replay key. | `accountId + titleType + normalizedTitleName + taxIdentityHash? + contactHash? + bankAccountHash?` |
| `UpdateInvoiceTitle` / `PUT /api/v1/invoice-titles/{titleId}` | Header UUID-v7 is stored as external replay key. | `titleId + expectedVersion + normalized mutable title fields hash` |
| `SetDefaultInvoiceTitle` / `POST /api/v1/invoice-titles/{titleId}/set-default` | Header UUID-v7 is stored as external replay key. | `accountId + titleId + commandPurpose:SET_DEFAULT` |
| `DeactivateInvoiceTitle` / `DELETE /api/v1/invoice-titles/{titleId}` | Header UUID-v7 is stored as external replay key. | `titleId + expectedVersion + reason` |
| `RequestEInvoice` / `POST /api/v1/e-invoice-requests` | Header UUID-v7 is stored as external replay key and copied into `clientRequestId`. | `orderId + titleId + titleVersion + invoiceScope hash + amountBasisHash + recipientEmailHash? + simSeedRef?` |
| SIM submit attempt | No public header; internal command only. | `invoiceRequestId + gatewayProfile + submitAttemptNo + requestFingerprint` |
| SIM result recording | No public header; internal command only. | `gatewayRequestId + gatewayStatus + gatewayAcceptedAt?/rejectionCode?/failureClass?` |

## Common enums

| Enum | Values |
|---|---|
| `titleType` | `PERSONAL`, `ENTERPRISE` |
| `titleStatus` | `ACTIVE`, `DEACTIVATED` |
| `invoiceRequestStatus` | `REQUESTED`, `AMOUNT_READY`, `SUBMITTED`, `ACCEPTED`, `ISSUED`, `RED_FLUSH_PENDING`, `RED_FLUSHED`, `CANCELLED`, `REJECTED`, `FAILED`, `EXPIRED` |
| `redFlushStatus` | `REQUESTED`, `BLOCKING_REFUND`, `SUBMITTED`, `ACCEPTED`, `COMPLETED`, `REJECTED`, `FAILED`, `CANCELLED` |
| `invoiceType` | `BLUE`, `RED` |
| `invoiceScopeType` | `ORDER`, `ORDER_ITEMS`, `SEGMENTS`, `TRAVELERS` |
| `simGatewayStatus` | `ACCEPTED`, `REJECTED`, `FAILED` |
| `refundRedFlushObservationStatus` | `NO_INVOICE`, `RED_FLUSH_COMPLETED`, `SUSPENDED`, `VIOLATION_OBSERVED` |
| `refundReleaseFlagStatus` | `ALLOWED`, `SUSPENDED`, `RELEASED_AFTER_RED_FLUSH`, `VIOLATION_OBSERVED` |

Terminal invoice states `RED_FLUSHED`, `CANCELLED`, `REJECTED`, `FAILED`, and
`EXPIRED`, plus terminal red-flush states `COMPLETED`, `REJECTED`, `FAILED`, and
`CANCELLED`, rest observable via GET. GET endpoints MUST return their final read
model instead of treating terminal state as an error.

## Resource representations

### InvoiceTitle

| Field | Type | Required | Description |
|---|---|---|---|
| `titleId` | string | yes | Canonical invoice title ID (`ivt-<uuid-v7>`). |
| `accountId` | string | yes | Owning account ID. |
| `titleType` | enum | yes | `PERSONAL` or `ENTERPRISE`. |
| `titleName` | string | yes | Display title. For personal titles this may be the verified display name or `个人`. |
| `taxIdentityMasked` | string | for ENTERPRISE | Masked taxpayer identity. Full values are not returned. |
| `taxIdentityHash` | string | for ENTERPRISE | Stable hash used for duplicate detection and audit. |
| `registeredAddress` | string | no | Enterprise registered address; omit when not supplied. |
| `registeredPhoneMasked` | string | no | Masked enterprise registered phone. |
| `bankName` | string | no | Enterprise bank name. |
| `bankAccountMasked` | string | no | Masked bank account. |
| `bankAccountHash` | string | no | Stable hash of bank account for duplicate detection. |
| `isDefault` | boolean | yes | Whether this title is the account default. |
| `status` | enum | yes | `ACTIVE` or `DEACTIVATED`. |
| `version` | integer | yes | Monotonic title version. Updating creates a new version. |
| `validFrom` | RFC3339 UTC | yes | Version validity start. |
| `validTo` | RFC3339 UTC | no | Version validity end when deactivated or superseded. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last update timestamp. |

### EInvoiceRequest

| Field | Type | Required | Description |
|---|---|---|---|
| `invoiceRequestId` | string | yes | Request ID (`ivr-<uuid-v7>`). |
| `accountId` | string | yes | Account requesting the invoice. |
| `orderId` | string | yes | Journey order ID being invoiced. |
| `titleId` | string | yes | Invoice title ID. |
| `titleVersion` | integer | yes | Frozen title version used for the request. |
| `invoiceScope` | object | yes | `InvoiceScope` table below. |
| `amountBasis` | object | yes | `AmountBasis` table below; sourced from Finance Settlement. |
| `recipientEmailMasked` | string | no | Masked e-mail for delivery/display. |
| `clientRequestId` | string | yes | UUID-v7 idempotency key from the API header. |
| `gatewayProfile` | string | yes | SIM gateway profile, default `SIM_TAX_BUREAU_CN_V1`. |
| `simSeedRef` | string | no | Optional deterministic SIM seed reference; not secret, not PII. |
| `status` | enum | yes | Invoice request status. |
| `eInvoiceId` | string | no | Issued e-invoice ID once created (`ein-<uuid-v7>`). |
| `rejectionCode` | string | no | Stable SIM or domain rejection code when status is `REJECTED`. |
| `failureClass` | string | no | Stable failure class when status is `FAILED`. |
| `createdAt` | RFC3339 UTC | yes | Request creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last transition timestamp. |

`InvoiceScope` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `scopeType` | enum | yes | `ORDER`, `ORDER_ITEMS`, `SEGMENTS`, or `TRAVELERS`. |
| `orderItemRefs` | string[] | no | Required when `scopeType=ORDER_ITEMS`; sorted for material folding. |
| `segmentRefs` | string[] | no | Required when `scopeType=SEGMENTS`; sorted for material folding. |
| `travelerRefs` | string[] | no | Required when `scopeType=TRAVELERS`; sorted for material folding. |

`AmountBasis` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `basisType` | enum | yes | `REVENUE_RECOGNITION`, `FINANCE_INVOICE`, or `MANUAL_APPROVED_SNAPSHOT`. |
| `revenueRecognitionIds` | string[] | no | Finance Settlement revenue recognition IDs included in the amount basis. |
| `financeInvoiceId` | string | no | Finance Settlement invoice/reference when `basisType=FINANCE_INVOICE`. |
| `taxLines` | array[object] | yes | Tax line snapshots with `taxCode`, `taxRateBasisPoints`, `taxableAmount` Money, and `taxAmount` Money. |
| `totalAmount` | Money | yes | Total positive invoice amount. |
| `amountBasisHash` | string | yes | SHA-256 of the canonical Finance Settlement amount snapshot. |

### EInvoice

| Field | Type | Required | Description |
|---|---|---|---|
| `eInvoiceId` | string | yes | E-invoice ID (`ein-<uuid-v7>`). |
| `invoiceRequestId` | string | yes | Source request ID. |
| `invoiceType` | enum | yes | `BLUE` for this API's issued invoice; `RED` only appears on red-flush read models. |
| `invoiceNumber` | string | no | SIM tax invoice number after issuance; immutable once present. |
| `gatewayRequestId` | string | yes | SIM request ID generated by Invoicing. |
| `gatewayProfile` | string | yes | SIM gateway profile. |
| `gatewayStatus` | enum | no | Last SIM status. |
| `downloadRef` | string | no | Opaque download/artifact reference; not a raw document or secret URL. |
| `totalAmount` | Money | yes | Issued amount. |
| `issuedAt` | RFC3339 UTC | no | Issuance timestamp. |
| `status` | enum | yes | Current invoice status. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last transition timestamp. |

### RedFlushView

| Field | Type | Required | Description |
|---|---|---|---|
| `redFlushId` | string | yes | Red-flush ID (`irf-<uuid-v7>`). |
| `originalInvoiceId` | string | yes | Issued blue invoice being red-flushed. |
| `postSalesCaseId` | string | yes | Post Sales case that triggered or exposed the refund. |
| `orderId` | string | yes | Journey order ID. |
| `refundFactEventId` | string | no | Consumed `PostSalesApplied` envelope ID when observationally detected. |
| `redInvoiceId` | string | no | Red invoice ID after completion. |
| `redInvoiceNumber` | string | no | SIM red invoice number after completion. |
| `status` | enum | yes | Red-flush status. |
| `refundRedFlushObservationStatus` | enum | yes | Current release/violation observation for the refund. |
| `refundReleaseFlagStatus` | enum | yes | Invoicing-local release flag. `SUSPENDED` is local only in Wave A and does not block Post Sales execution. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last transition timestamp. |

### ItineraryReceiptProjection

| Field | Type | Required | Description |
|---|---|---|---|
| `itineraryReceiptId` | string | yes | Deterministic projection ID (`itr-<uuid-v7>`). |
| `orderId` | string | yes | Journey order ID. |
| `travelerRefs` | string[] | yes | Traveler references included in the receipt. |
| `segmentRefs` | string[] | yes | Segment references included in the receipt. |
| `receiptVersion` | integer | yes | Projection/template version. |
| `receiptNo` | string | yes | Human receipt number, deterministic for the same projection material. |
| `displayNameMasked` | string | no | Masked or user-confirmed traveler display name. |
| `travelSummary` | object | yes | Read-only order/segment summary. Must not include unmasked document numbers. |
| `artifactRef` | string | yes | Opaque artifact reference for rendering/downloading. |
| `generatedAt` | RFC3339 UTC | yes | Projection generation timestamp. |
| `sourceMaterialHash` | string | yes | SHA-256 of order, traveler, segment, and template material. |

## Endpoints

### Create Invoice Title

**POST** `/api/v1/invoice-titles`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Owning account ID. |
| `titleType` | enum | yes | `PERSONAL` or `ENTERPRISE`. |
| `titleName` | string | yes | Display title. |
| `taxIdentity` | string | for ENTERPRISE | Full taxpayer identity in request only; service stores hash and masked display. |
| `registeredAddress` | string | no | Enterprise registered address. |
| `registeredPhone` | string | no | Enterprise registered phone; returned masked. |
| `bankName` | string | no | Enterprise bank name. |
| `bankAccount` | string | no | Enterprise bank account; returned masked/hashed only. |
| `setAsDefault` | boolean | no | Whether to make this title the default for the account. Default false. |

**Response (201):** `InvoiceTitle`.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `DOMAIN_RULE_VIOLATION` is returned when an enterprise title lacks a taxpayer
  identity or the input includes prohibited unmasked document artifacts beyond
  allowed title fields.
- `CONFLICT` is returned when the normalized active title already exists for the
  same account and is not an idempotent replay.

### List Invoice Titles

**GET** `/api/v1/invoice-titles?accountId={accountId}&status=ACTIVE&limit=20&offset=0`

`accountId` is required. Broad unfiltered listing is not part of this API.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Owning account ID. |
| `status` | enum | no | Optional `ACTIVE` or `DEACTIVATED`; default `ACTIVE`. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is an `InvoiceTitle`.

**Error codes:** `VALIDATION_FAILED`

### Get Invoice Title

**GET** `/api/v1/invoice-titles/{titleId}`

**Response (200):** `InvoiceTitle`, including deactivated/resting titles.

**Error codes:** `NOT_FOUND`

### Update Invoice Title

**PUT** `/api/v1/invoice-titles/{titleId}`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedVersion` | integer | yes | Current title version expected by the caller. |
| `titleName` | string | yes | New display title. |
| `taxIdentity` | string | for ENTERPRISE | Full taxpayer identity in request only when changing enterprise tax identity. |
| `registeredAddress` | string | no | Enterprise registered address. |
| `registeredPhone` | string | no | Enterprise registered phone. |
| `bankName` | string | no | Enterprise bank name. |
| `bankAccount` | string | no | Enterprise bank account. |

**Response (200):** Updated `InvoiceTitle` with incremented `version`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `PRECONDITION_FAILED` is returned when `expectedVersion` does not match or the
  title is deactivated.

### Set Default Invoice Title

**POST** `/api/v1/invoice-titles/{titleId}/set-default`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Owning account ID; must match the title. |

**Response (200):** `InvoiceTitle` with `isDefault=true`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `PRECONDITION_FAILED`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Deactivate Invoice Title

**DELETE** `/api/v1/invoice-titles/{titleId}`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedVersion` | integer | yes | Current title version expected by the caller. |
| `reason` | string | yes | Deactivation reason; must not include sensitive personal data. |

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `titleId` | string | Deactivated title ID. |
| `status` | enum | `DEACTIVATED`. |
| `deactivatedAt` | RFC3339 UTC | Deactivation timestamp. |

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Request Blue E-Invoice

**POST** `/api/v1/e-invoice-requests`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

The endpoint creates an invoice request, attaches the supplied Finance Settlement
amount basis, and submits a blue invoice to the SIM tax-bureau gateway. The SIM
outcome is deterministic. If accepted and issued synchronously, the response may
already include `eInvoiceId` and status `ISSUED`; otherwise clients poll the GET
endpoints. Deterministic SIM business rejection returns a successful resource with
status `REJECTED`, not a 5xx.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `accountId` | string | yes | Account requesting the invoice. |
| `orderId` | string | yes | Journey order ID. |
| `titleId` | string | yes | Active invoice title ID. |
| `titleVersion` | integer | yes | Expected title version to freeze into the request. |
| `invoiceScope` | object | yes | `InvoiceScope` shape above. |
| `amountBasis` | object | yes | `AmountBasis` shape above; must come from Finance Settlement facts/read model. |
| `recipientEmail` | string | no | Delivery e-mail; returned masked only. |
| `gatewayProfile` | string | no | SIM gateway profile; default `SIM_TAX_BUREAU_CN_V1`. |
| `simSeedRef` | string | no | Optional deterministic seed ref for tests/replay; never a secret. |

**Response (201):** `EInvoiceRequest`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`,
`PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`,
`UNAVAILABLE`

- `NOT_FOUND` is returned when the title, order snapshot, or Finance Settlement
  amount basis reference cannot be found.
- `PRECONDITION_FAILED` is returned when `titleVersion` is not current/usable.
- `CONFLICT` is returned when the same order/scope/amount basis already has an
  active or issued invoice request not covered by idempotent replay.
- `DOMAIN_RULE_VIOLATION` is returned for over-invoicing, unsupported scope, or
  mismatched amount totals/currency.
- `UNAVAILABLE` is only for the Invoicing service or required local dependency
  being unavailable; deterministic SIM rejection is represented on the resource.

### Get E-Invoice Request

**GET** `/api/v1/e-invoice-requests/{invoiceRequestId}`

**Response (200):** `EInvoiceRequest`, including terminal requests.

**Error codes:** `NOT_FOUND`

### Get E-Invoice

**GET** `/api/v1/e-invoices/{eInvoiceId}`

**Response (200):** `EInvoice`, including terminal/resting invoices.

**Error codes:** `NOT_FOUND`

### List Invoices by Order

**GET** `/api/v1/e-invoices?orderId={orderId}&limit=20&offset=0`

`orderId` is required. Broad unfiltered listing is not part of this API.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `orderId` | string | yes | Journey order ID. |
| `invoiceType` | enum | no | Optional `BLUE` or `RED`. |
| `status` | enum | no | Optional invoice status filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response of `EInvoice` resources.

**Error codes:** `VALIDATION_FAILED`

### Get Red-Flush View

**GET** `/api/v1/red-flushes/{redFlushId}`

**Response (200):** `RedFlushView`, including terminal red-flushes.

**Error codes:** `NOT_FOUND`

### List Red-Flush Views by Order

**GET** `/api/v1/red-flushes?orderId={orderId}&postSalesCaseId={postSalesCaseId}&limit=20&offset=0`

At least one of `orderId` or `postSalesCaseId` is required.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `orderId` | string | conditional | Journey order ID. |
| `postSalesCaseId` | string | conditional | Post Sales case ID. |
| `status` | enum | no | Optional red-flush status filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response of `RedFlushView` resources.

**Error codes:** `VALIDATION_FAILED`

### Generate Itinerary Receipt Projection

**GET** `/api/v1/itinerary-receipts/generated?orderId={orderId}&travelerRefs={travelerRefs}&segmentRefs={segmentRefs}&receiptVersion=1`

Read-only deterministic projection endpoint. The service may render/cache an
artifact behind `artifactRef`, but it MUST NOT process a state-changing command
or publish an itinerary domain event from this GET. `travelerRefs` and
`segmentRefs` are comma-separated lists in the query string and are normalized by
sorting for `sourceMaterialHash`.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `orderId` | string | yes | Journey order ID. |
| `travelerRefs` | string | yes | Comma-separated traveler references. |
| `segmentRefs` | string | yes | Comma-separated segment references. |
| `receiptVersion` | integer | no | Template/projection version, default 1. |

**Response (200):** `ItineraryReceiptProjection`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `PRECONDITION_FAILED`,
`UNAVAILABLE`

- `PRECONDITION_FAILED` is returned when the order is not confirmed or the
  requested traveler/segment material cannot be proven from order/entitlement
  facts.

## Bus-only behavior

The following commands from `docs/02-domains/invoicing.md` have no public HTTP
endpoint in this Wave-A contract:

| Command | Trigger | Description |
|---|---|---|
| `SubmitEInvoice` | Invoicing application flow after `RequestEInvoice`. | Sends deterministic SIM blue-invoice request. |
| `RecordGatewayAccepted` / `RecordGatewayRejected` / `RecordGatewayFailed` / `MarkEInvoiceIssued` | SIM adapter callback/query result. | Records deterministic SIM outcome. |
| `RequestRedFlush` / `SubmitRedFlush` / `RecordRedFlushAccepted` / `RecordRedFlushRejected` / `RecordRedFlushFailed` / `CompleteRedFlush` | Invoicing consumer/scheduler after Post Sales refund facts or manual audit. | Maintains red-flush lifecycle. No Post Sales blocking hook is added in this wave. |
| `GenerateItineraryReceipt` / `ReissueItineraryReceipt` / `RevokeItineraryReceipt` | Deferred write-model behavior. | Not exposed; Wave-A uses read-only projection GET only. |
| `CreateReimbursementExport` and related artifact commands | Deferred. | Reimbursement export write APIs are outside this wave. |

## Existing-domain contract increments

No existing domain payload shape is changed by this API file. Invoicing consumes
the existing `PostSalesApplied` event name from Post Sales and existing Finance
Settlement/Journey Order facts.

需同波实现 (when Wave-A code is activated): implementation touchpoints are inside
Invoicing only — HTTP validators for invoice title/e-invoice requests, command
idempotency material folding, SIM tax-bureau adapter seed logic, red-flush read
model, and the Post Sales event consumer that interprets `PostSalesApplied`
`resultSummary` refund material. Post Sales has no same-wave blocking-hook code
touchpoint; adding one later would require Post Sales execution validation and
refund orchestration changes in the same future wave.
