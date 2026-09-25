# Post Sales HTTP API

Last updated: 2026-09-24.

Runtime baseline: Helm revision 279.
Remaining work is tracked in [TODO](../../TODO.md).

## Overview

Post Sales manages cancellation, refund, change and compensation cases.
It evaluates eligibility, approves or rejects cases, and coordinates downstream ticket, reservation, payment and wallet operations.

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
| `createdAt` | timestamp? | Persisted creation time; null when the original timestamp is unavailable. |

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `REFUND_ALREADY_IN_PROGRESS`, `ORDER_NOT_REFUNDABLE`, `DOMAIN_RULE_VIOLATION`

An exclusive refund, cancellation or change cannot reuse a ticket or booking already covered by an applied exclusive case.
Such requests return `ORDER_NOT_REFUNDABLE`.
Replaying the original idempotency key returns the original case, and independent compensation requests remain available.

### Evaluate Post-Sales Eligibility

The production adjustment quote adapter reads the current accepted Journey Order, including traveler snapshots, segment departures and monetary summary.
It validates those references and returns the policy inputs with the quote result.
Evaluation uses those accepted inputs and the recorded count of applied changes.
It does not require the asynchronous order projection to have arrived when the synchronous order facts are available.
Malformed or unavailable order facts fail evaluation.
External quote and accepted order reads run without a database transaction.
Saving the decision acquires the order lock, reloads the current case, validates unchanged scope and target, and commits the decision and events in a short transaction.
Concurrent evaluations return the same persisted decision and do not publish duplicate decision events.

**POST** `/api/v1/post-sales-cases/{caseId}/evaluate`

**Idempotency:** REQUIRED

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `caseId` | string | Case ID. |
| `eligible` | boolean | Whether the request is eligible. |
| `adjustmentQuoteId` | string | Reference to fare-pricing adjustment quote. |
| `refundableAmount` | object | Amount refundable (Money); zero in the compensation currency for `COMPENSATION`. |
| `compensationAmount` | object | Wallet credit amount (Money), present for `COMPENSATION`. |
| `amountDue` | object | Amount due (Money). |

For compensation, the amount comes from the frozen decision snapshot and is the amount used by wallet execution after approval.
The same amount fields appear in the case details under `decision`.

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

Approval starts compensation execution internally as `EXECUTING` and returns public status `APPROVED`.
The caller advances the approved case through `execute-compensation`.

For an existing case already in internal status `EXECUTING`, a new approval request is an execution redrive.
The service rebuilds `PostSalesApproved` from the frozen decision, scope, refund payment allocation and approved actions, and rebuilds `PostSalesExecutionStarted` from the persisted execution plan.
The first redrive stores one stable event identity pair in the case snapshot; every repeated or concurrent redrive reuses the same pair.
This allows the pair to be put back into the outbox after Redis and published-outbox retention have removed the original messages without creating distinct approval facts.
Use a new HTTP `Idempotency-Key` for each redrive attempt because repeating a completed HTTP key returns its stored response without entering the application service.

Redrive does not change the case state or approved amount.
Payment uses the canonical case ID, or `caseId:paymentIntentId` for split refunds, as its refund idempotency key.
Entitlement voiding carries the same case ID and is a no-op for an already voided entitlement; capacity release remains guarded by the owning booking/capacity state.
An `APPLIED` case returns its existing response and publishes no approval or execution-start event.

### Get Post-Sales Case

**GET** `/api/v1/post-sales-cases/{caseId}`

**Response (200):** Full case details.

Case details include `decision`, `compensationOperations` and `releasedOrderItemRefs`.
For wallet compensation, `decision.kind=COMPENSATION`, `decision.compensationAmount` is the approved credit and `decision.refundableAmount` is zero.
`compensationOperations` exposes the persisted execution evidence described below.
`releasedOrderItemRefs` contains recorded capacity release confirmations and remains empty for wallet compensation.
For ordinary refunds and cancellations, all scoped capacity releases can complete the case as `APPLIED`.
Payment refund settlement remains a separate fact and must be checked through the Payment API.

**Error codes:** `NOT_FOUND`

### Execute approved wallet compensation

**POST** `/api/v1/post-sales-cases/{caseId}/execute-compensation`

**Idempotency:** REQUIRED (`Idempotency-Key` as UUID v7).
Each advance uses a new key because the HTTP filter replays the stored response for a repeated key and request.
Wallet issuance uses a stable downstream key derived from the canonical case ID and `wallet-compensation` operation name.
The endpoint accepts an empty request body and returns full case details with HTTP 200.

Execution requires an evaluated `COMPENSATION` case in internal status `EXECUTING`.
The approved execution plan must contain `ISSUE_COMPENSATION_CREDIT` before active execution can proceed.
The public status remains `APPROVED` while operations are pending and becomes `APPLIED` after verification and completion.
An evaluated compensation case in `FAILED` returns its recorded state. An
`APPLIED` compensation case is read-only reverified against its original
rights, Payment facts and Wallet benefit so historical terminal mismatches are
not treated as successful merely because the case status is terminal.
Concurrent calls share the case execution guard with change execution, including its sixteen execution permits and PostgreSQL advisory lock.
When a permit or the case lock is busy, the call returns the current case without advancing it.

Before each active advance, the executor checks the original order identity and requires its status to be outside `CANCELLED`, `REFUNDED` and `FAILED`.
Each scoped entitlement must belong to that order and have a status other than `VOIDED`.
The case must have no recorded capacity releases, and `GET /api/v1/refunds?businessCaseRef={caseId}` must return `total=0` for the canonical case ID.
`GET /api/v1/payment-intents/by-business/{journeyOrderId}` must return that order's original payment with `businessRef=journeyOrderId`, status `CAPTURED` and zero `refundedAmount`.
These checks preserve the original travel references while the executor performs wallet operations.
The execution path contains no ticket void, seat release, order cancellation or payment refund operation.

Each successful advance persists one evidence record or completes the case:

| Stage | Persisted evidence | Public status after this advance |
|---|---|---|
| Prepare request | `compensationOperations.request` contains the full wallet issuance request, including recipient, approved amount, rules, case reference and fixed validity timestamps. | `APPROVED` |
| Issue positive amount | `compensationOperations.issued` contains the wallet response after checking its `benefitId`, account, case, source, benefit type, balance type and issued Money against the request and decision. | `APPROVED` |
| Verify result | `compensationOperations.verified` contains `originalTicketsPreserved=true`, `originalPaymentPreserved=true`, original `paymentIntentId` and `capturedAmount`, approved `amount` and `benefitId`. | `APPROVED` |
| Complete case | `completeCompensation` records `ISSUE_COMPENSATION_CREDIT` and `APPLY_RESULT` as `SUCCEEDED` in order, then the domain applies the case and emits `PostSalesApplied`. | `APPLIED` |

The request credits the order's `accountId` with `benefitType=COMPENSATION_CREDIT`, `balanceType=PROMOTION_CREDIT` and `issuanceSource=POST_SALES_COMP`.
It carries canonical `caseId`, `applicableScope={scopeType: ANY_TRIP, currency: <approved currency>}`, `redemptionRule={singleUse: false, requiresReservation: false}` and an empty `revocationRule` request object.
The business reason is `{reasonType: POST_SALES_COMP, reasonCode: <case reasonCode>, referenceType: POST_SALES_CASE, referenceId: <caseId>}`.
The current executor sets `validFrom` to the time it creates the recorded request and `validUntil` to 30 days later.
Retries reuse the persisted request, including both timestamps.
After request creation, the executor checks that its account and amount still match the order and approved decision.
Positive amounts are reread through `GET /api/v1/benefits/{benefitId}` on advances after issuance, including the completion advance.

For a zero approved amount, execution records `request` and `verified`, with `verified.benefitId=""`, and completes without calling wallet issuance or creating an `issued` record.
The credit step references the issued `benefitId`, or `zero-approved-compensation` for a zero amount, and `APPLY_RESULT` references the original order ID.
The `verified` flags record the executor's ticket, original payment and case refund checks; loadgen separately checks confirmed original Holds, order status and the original captured total through their owning APIs.
Operation records are saved in the PostgreSQL case snapshot and restored on reload.

Wallet / Promotion deduplicates `POST_SALES_COMP` issuance by `caseId` and the complete issuance command.
An identical command returns the existing benefit even with a different HTTP idempotency key, while a different command returns HTTP 409 `CONFLICT` from the wallet API.
Before issuing, the executor uses `GET /api/v1/internal/benefits/by-case/{caseId}`.
An existing matching benefit is adopted into `compensationOperations.issued`,
closing the Wallet-commit/Post-Sales-crash window without a second issuance.
An unsuccessful downstream response remains retryable and does not create a
terminal checkpoint.

When successful authoritative reads prove a contradiction, Post Sales saves a
first-write-wins `compensation_mismatch_checkpoints` row and returns HTTP 409
`COMPENSATION_MISMATCH`. The response includes the case, mismatch code,
approved amount, detection time and `automaticReplayAllowed=false`. Later calls
return the same checkpoint before any Payment or Wallet request.

**GET** `/api/v1/internal/compensation-mismatches/{caseId}` returns the complete
checkpoint, including refund, Wallet, rights and local operation evidence, or
HTTP 404 when no terminal mismatch is recorded.

**Execution errors:** Invalid case type or state raises HTTP 422
`DOMAIN_RULE_VIOLATION`; a proven terminal contradiction returns 409
`COMPENSATION_MISMATCH`; an unsuccessful downstream read or issuance response
raises HTTP 500 `INTERNAL_ERROR` and remains retryable.

### Execute an approved change

**POST** `/api/v1/post-sales-cases/{caseId}/execute-change`

Advances one recorded operation and returns full case details.
Callers repeat this operation while execution is pending.
The service derives stable downstream idempotency keys from the case and operation names, and persists accepted results in `changeOperations`.
An `APPLIED` or `FAILED` case returns its current state without issuing another operation.
If the target Offer expires before any replacement operation starts, execution verifies the original issued tickets and ends with `CHANGE_OFFER_EXPIRED`.
The case stores `target-expired` and `expired-target-completed`; it creates no replacement reservation or difference payment.

The service records the target Offer, itinerary, fare and funding source, requests replacement bookings, and waits for their confirmed Holds.
All traveler replacement reservations are submitted before waiting for confirmations, and each confirmed booking is recorded independently.
It then collects any required fare difference, issues replacement tickets, voids original tickets, waits for original reservations to release, settles any approved difference refund, and applies the order change.
Original tickets remain available until the replacement ticket stage succeeds.
The public status `APPROVED` covers the internal approved and executing states.
Use operation evidence and the terminal case status to determine completion.

An expired, cancelled or failed difference payment with no captured funds triggers replacement reservation cancellation.
An uncaptured intent whose `expiresAt` has elapsed is cancelled before another capture attempt.
A capture rejection at the expiry boundary is recorded as `difference-rejected` and the next execution verifies the actual payment state before compensation.
The service checks that original tickets remain issued and persists `unpaid-compensation-completed` before returning `FAILED` with `terminalReason=CHANGE_PAYMENT_TERMINATED`.
An accepted no capacity rejection from Booking or Ticketing is persisted as `capacity-rejected`.
Execution then lists replacement tickets, voids any issued replacements, cancels replacement reservations and waits for their Hold release.
Captured difference payments are refunded in full and checked for settlement; uncaptured payment intents are cancelled.
The original tickets must remain issued and no original ticket void or order application may have started.
`capacity-compensation-completed` records the checked result before the case ends as `FAILED` with `terminalReason=CHANGE_CAPACITY_UNAVAILABLE`.
Concurrent execution calls for one case share a PostgreSQL advisory execution lock; a busy call returns the current case without another downstream operation.
Execution locks use a dedicated pool of sixteen PostgreSQL connections initialized during service startup.
Different cases can execute concurrently without holding the ordinary business pool's connections across downstream calls.
The business transactions and event consumers continue to use their own pool.

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

## Current verification

Verification results are recorded in [current status](../../00-current-status.md).
Recorded change and compensation operations support retry and restart recovery.
The accepted supply integration remains inactive in production at Helm revision 279.
