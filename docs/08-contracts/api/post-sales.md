# Post Sales HTTP API

Last updated: 2026-09-23.

## Overview

Post Sales manages cancellation, refund, change and compensation cases.
It evaluates eligibility, approves or rejects cases, and coordinates downstream ticket, reservation and payment operations.

Field shapes reference docs/08-contracts/shared-primitives.md for IDs, timestamps, and Money.

## Endpoints

### Select a Change Target

`POST /api/v1/post-sales-cases/{caseId}/target-offer` accepts `targetOfferId` and requires `Idempotency-Key`.
The selection is persisted before evaluation and repeated identical selections return the same case.
A different selection or a selection after evaluation is rejected.
The case details include `targetOfferId`.
Evaluation resolves the exact target fare quote from that offer and checks account, travelers and validity.

### Open Post-Sales Case

**POST** `/api/v1/post-sales-cases`

**Idempotency:** REQUIRED

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | string | yes | Parent order ID (`ord-<uuid>`). |
| `caseType` | enum | yes | `CANCELLATION`, `REFUND`, `CHANGE`, `REBOOK`, `COMPENSATION` |
| `scope` | object | yes | Affected order items, segments, travelers, entitlements. |
| `reasonCode` | string | yes | Business reason for the case. |
| `actorRef` | string | yes | Who requested the case (account ID or operator ID). |

**Response (201):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Canonical case ID (`psc-<uuid>`). |
| `journeyOrderId` | string | Parent order. |
| `caseType` | enum | Case type. |
| `status` | enum | `OPENED`, `EVALUATING`, `APPROVED`, `REJECTED`, `APPLIED`, `FAILED` |
| `releasedOrderItemRefs` | string[] | Persisted capacity release confirmations within the case scope. All scoped ticket reservations must be released before the capacity execution phase completes. |
| `createdAt` | timestamp? | Persisted creation time; null when the original timestamp is unavailable. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `REFUND_ALREADY_IN_PROGRESS`, `ORDER_NOT_REFUNDABLE`, `DOMAIN_RULE_VIOLATION`

An exclusive refund, cancellation or change cannot reuse a ticket or booking already covered by an applied exclusive case.
Such requests return `ORDER_NOT_REFUNDABLE`.
Replaying the original idempotency key returns the original case, and independent compensation requests remain available.

### Evaluate Post-Sales Eligibility

**POST** `/api/v1/post-sales-cases/{caseId}/evaluate`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Case ID. |
| `eligible` | boolean | Whether the request is eligible. |
| `adjustmentQuoteId` | string | Reference to fare-pricing adjustment quote. |
| `refundableAmount` | object | Amount refundable (Money). |
| `amountDue` | object | Amount due (Money). |

**Error codes:** `NOT_FOUND`, `ORDER_NOT_REFUNDABLE`, `PRECONDITION_FAILED`, `UNAVAILABLE`

### Approve Post-Sales Case

**POST** `/api/v1/post-sales-cases/{caseId}/approve`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Case ID. |
| `status` | enum | `APPROVED` |

**Error codes:** `NOT_FOUND`, `ORDER_NOT_REFUNDABLE`, `PRECONDITION_FAILED`

### Get Post-Sales Case

**GET** `/api/v1/post-sales-cases/{caseId}`

**Response (200):** Full case details.

**Error codes:** `NOT_FOUND`

### Execute an approved change

**POST** `/api/v1/post-sales-cases/{caseId}/execute-change`

Advances one recorded operation and returns full case details.
Callers repeat this operation while execution is pending.
The service derives stable downstream idempotency keys from the case and operation names, and persists accepted results in `changeOperations`.
An `APPLIED` or `FAILED` case returns its current state without issuing another operation.

The service records the target Offer, itinerary, fare and funding source, requests replacement bookings, and waits for their confirmed Holds.
It then collects any required fare difference, issues replacement tickets, voids original tickets, waits for original reservations to release, settles any approved difference refund, and applies the order change.
Original tickets remain available until the replacement ticket stage succeeds.
The public status `APPROVED` covers the internal approved and executing states.
Use operation evidence and the terminal case status to determine completion.

An expired, cancelled or failed difference payment with no captured funds triggers replacement reservation cancellation.
The service checks that original tickets remain issued and persists `unpaid-compensation-completed` before returning `FAILED` with `terminalReason=CHANGE_PAYMENT_TERMINATED`.
An accepted no capacity rejection from Booking or Ticketing is persisted as `capacity-rejected`.
Execution then lists replacement tickets, voids any issued replacements, cancels replacement reservations and waits for their Hold release.
Captured difference payments are refunded in full and checked for settlement; uncaptured payment intents are cancelled.
The original tickets must remain issued and no original ticket void or order application may have started.
`capacity-compensation-completed` records the checked result before the case ends as `FAILED` with `terminalReason=CHANGE_CAPACITY_UNAVAILABLE`.
Concurrent execution calls for one case share a PostgreSQL advisory execution lock; a busy call returns the current case without another downstream operation.

### Read replacement issue authorization

**GET** `/api/v1/post-sales-cases/{caseId}/issue-authorization/{bookingId}`

Requires `X-Correlation-Id`.
Checks the executing case, confirmed replacement booking, order balance and required captured difference payment.
Returns `authorized`, `caseId`, `journeyOrderId`, `segmentBookingId`, `segmentRef`, `travelerRef` and `paymentIntentId`.

### Read change settlement

**GET** `/api/v1/post-sales-cases/{caseId}/settlement`

Requires `X-Correlation-Id`.
Checks settled refund identities, funding ownership and the approved refund total.
Returns `journeyOrderId`, `caseId`, `balance` and the contributing `payments`.

## Internal execution operations

The domain execution operations include:

| Command | Trigger | Description |
|---|---|---|
| `QuotePostSalesDecision` | post-sales (internal) | Quote a decision with amounts and eligibility for a post-sales case. |
| `StartPostSalesExecution` | post-sales (internal) | Start execution of an approved post-sales case. |
| `ApplyPostSalesResult` | post-sales (internal) | Apply the result of post-sales execution to the case. |

## Integration status

The recorded change operations support retry and restart recovery.
Capacity compensation requires the corresponding Booking cancellation endpoint and validated loadgen handling before production activation.
Partial ticket issuance and captured difference payment paths require live scenario verification.
See [capacity integration requirements](../../10-domain-enrichment/capacity-overbooking.md) for the production activation requirements.
