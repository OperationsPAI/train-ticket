# Payment Channel — HTTP API

Last updated: 2026-07-10

## Overview

Payment Channel owns the ADR-0003 Wave A simulated payment-channel boundary for
`ALIPAY_SIM`, `WECHAT_SIM`, and `UNIONPAY_SIM`. It records channel-side payment
orders, original-route refunds, deterministic daily SIM statements, and channel
reconciliation discrepancies. Payment remains the owner of platform money
semantics; Finance Settlement remains the owner of accounting and reconciliation
case outcomes.

RULING (ADR-0003 Wave A): this contract intentionally narrows the channel scope
to the three SIM channels above. SIM behavior is deterministic and seedable; no
real payment network, real channel credential, or real signature secret is part
of this contract.

Field shapes reference `docs/08-contracts/api/README.md`. Money is always `{currency, minorUnits}`;
timestamps are RFC3339 UTC; JSON fields are camelCase; enum values are
SCREAMING_SNAKE_CASE. State-changing endpoints require `Idempotency-Key` with
UUID-v7 shape. `X-Correlation-Id`, when present on the wire for this context,
uses the `corr-<uuid-v7>` form; internally created command identifiers use
`cmd-<uuid-v7>`.

## Same-wave cross-context increments（需同波实现）

This API is not a docs-only island. The following increments MUST be implemented
in the same wave as Payment Channel:

| Context | Contract increment | Required code touchpoints |
|---|---|---|
| Payment | Capture and refund handoff carries `channelRef`; Payment consumes terminal channel facts and only then publishes its own `PaymentCaptured`, `PaymentFailed`, `RefundSettled`, or `RefundFailed` facts. | Payment channel enum/validation for `ALIPAY_SIM`, `WECHAT_SIM`, `UNIONPAY_SIM`; capture/refund command body validation; persistence/read-model field for `channelRef`; event payload serializer/deserializer; idempotency material folding for capture/refund. |
| Finance Settlement | Consumes `ChannelStatementGenerated` and `ChannelStatementFrozen` to reconcile daily channel statements; may open existing reconciliation cases from discrepancies. | Finance event consumer registration; statement payload validator; mapping from Payment Channel `differenceType` SCREAMING_SNAKE values to existing finance difference strings; consumed-event dedup by envelope `eventId`; reconciliation case command validation. |

## Common enums

| Enum | Values |
|---|---|
| `channel` | `ALIPAY_SIM`, `WECHAT_SIM`, `UNIONPAY_SIM` |
| `channelOrderStatus` | `CREATED`, `SUBMITTED`, `ACCEPTED`, `SUCCEEDED`, `FAILED`, `MISSED` |
| `channelRefundStatus` | `CREATED`, `SUBMITTED`, `ACCEPTED`, `SUCCEEDED`, `FAILED`, `MISSED` |
| `statementStatus` | `GENERATED`, `FROZEN`, `MATCHING`, `MATCHED`, `DISCREPANCY_FOUND`, `CLOSED` |
| `statementLineType` | `PAYMENT`, `REFUND`, `FEE`, `ADJUSTMENT` |
| `statementLineStatus` | `PRESENT`, `MISSING`, `DUPLICATE`, `LATE`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH` |
| `differenceType` | `MISSING_IN_CHANNEL`, `MISSING_IN_PLATFORM`, `AMOUNT_MISMATCH`, `CURRENCY_MISMATCH`, `STATUS_MISMATCH`, `DUPLICATE`, `REFUND_LAG`, `LATE_PAYMENT` |
| `discrepancyStatus` | `OPENED`, `INVESTIGATING`, `MANUAL_REVIEW`, `RESOLVED`, `REJECTED` |
| `simScenarioCode` | `NORMAL`, `MISSED_ORDER`, `MISSED_REFUND`, `AMOUNT_MISMATCH`, `STATUS_MISMATCH`, `DUPLICATE_LINE`, `REFUND_LAG`, `STATEMENT_DELAY` |

`SUCCEEDED`, `FAILED`, `MISSED`, `CLOSED`, `RESOLVED`, and `REJECTED` are
restful terminal states for their resources: GET requests keep returning the
same terminal representation and MUST NOT reopen the resource.

## Common payload objects

### ChannelRef

`channelRef` is the cross-context handoff reference used by Payment and Payment
Channel. It is a reference, not proof that the platform payment is captured or
refunded.

| Field | Type | Required | Description |
|---|---|---|---|
| `channel` | enum | yes | `ALIPAY_SIM`, `WECHAT_SIM`, or `UNIONPAY_SIM`. |
| `channelOrderId` | string | no | Payment Channel order ID (`cho-<uuid-v7>`) once created. Required in responses after a channel order exists. |
| `channelRefundId` | string | no | Payment Channel refund ID (`chr-<uuid-v7>`) for refund handoff responses. |
| `channelTransactionId` | string | no | SIM payment transaction reference once known. |
| `channelRefundTransactionId` | string | no | SIM refund transaction reference once known. |
| `channelStatementId` | string | no | Statement that later contains or reconciles the line. |
| `faultSeedRef` | string | no | Deterministic SIM seed reference for non-production scenario control; never a credential. |

### FaultSeed

| Field | Type | Required | Description |
|---|---|---|---|
| `seedVersion` | string | yes | Stable seed version used by gateway and statement generation. |
| `scenarioCode` | enum | yes | One of `simScenarioCode`. |
| `seedMaterialHash` | string | yes | Hash of normalized seed material. Do not store raw PII. |
| `effectiveFrom` | RFC3339 UTC | no | Optional start time for applying the seed. |
| `effectiveUntil` | RFC3339 UTC | no | Optional end time for applying the seed. |

### ChannelAttempt

| Field | Type | Required | Description |
|---|---|---|---|
| `attemptNo` | integer | yes | 1-based attempt number within the order or refund. |
| `attemptType` | enum | yes | `SUBMIT` or `QUERY`. |
| `requestFingerprint` | string | yes | Hash of normalized request material. |
| `simStatus` | string | yes | Raw normalized SIM status such as `ACCEPTED`, `SUCCESS`, `FAILED`, or `NOT_FOUND`. |
| `providerErrorCode` | string | no | Normalized SIM/provider error code. |
| `retryable` | boolean | yes | Whether the failure can be retried without creating a new external side effect. |
| `attemptedAt` | RFC3339 UTC | yes | Attempt timestamp. |

## Resource representations

### ChannelOrder

| Field | Type | Required | Description |
|---|---|---|---|
| `channelOrderId` | string | yes | Channel order ID (`cho-<uuid-v7>`). |
| `paymentIntentId` | string | yes | Payment intent reference (`pi-<uuid-v7>`). |
| `businessRef` | string | yes | Payment business reference snapshot, normally order reference. |
| `purpose` | string | yes | Payment purpose snapshot. |
| `channel` | enum | yes | SIM channel. |
| `amount` | Money | yes | Requested channel payment amount. |
| `status` | enum | yes | Channel order status. |
| `idempotencyKey` | string | yes | API header key or folded internal key recorded for this creation. |
| `requestFingerprint` | string | yes | Folded creation material hash. |
| `sourceCommandId` | string | yes | Creating command ID in `cmd-<uuid-v7>` form. |
| `correlationId` | string | yes | Correlation ID in `corr-<uuid-v7>` form. |
| `channelTransactionId` | string | no | SIM transaction ID after acceptance/success. |
| `acceptedAt` | RFC3339 UTC | no | SIM acceptance timestamp. |
| `completedAt` | RFC3339 UTC | no | Terminal success/failure/missed timestamp. |
| `faultSeedRef` | string | no | Seed reference controlling deterministic SIM behavior. |
| `attempts` | array[ChannelAttempt] | yes | Submit/query attempts, newest last. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |
| `version` | integer | yes | Aggregate version after latest mutation. |

### ChannelRefund

| Field | Type | Required | Description |
|---|---|---|---|
| `channelRefundId` | string | yes | Channel refund ID (`chr-<uuid-v7>`). |
| `refundId` | string | yes | Payment refund reference (`rf-<uuid-v7>` or existing Payment refund ID shape). |
| `paymentIntentId` | string | yes | Original payment intent. |
| `channelOrderId` | string | yes | Original successful channel order. |
| `originalChannelTransactionId` | string | yes | Original SIM payment transaction. |
| `channel` | enum | yes | Same channel as the original order. |
| `amount` | Money | yes | Refund amount; must be positive and same currency as original order. |
| `refundReasonCode` | string | yes | Reason code supplied by Payment/Post Sales. |
| `status` | enum | yes | Channel refund status. |
| `idempotencyKey` | string | yes | API header key or folded internal key recorded for this creation. |
| `requestFingerprint` | string | yes | Folded creation material hash. |
| `sourceCommandId` | string | yes | Creating command ID in `cmd-<uuid-v7>` form. |
| `correlationId` | string | yes | Correlation ID in `corr-<uuid-v7>` form. |
| `channelRefundTransactionId` | string | no | SIM refund transaction ID after acceptance/success. |
| `acceptedAt` | RFC3339 UTC | no | SIM refund acceptance timestamp. |
| `completedAt` | RFC3339 UTC | no | Terminal success/failure/missed timestamp. |
| `faultSeedRef` | string | no | Seed reference controlling deterministic SIM behavior. |
| `attempts` | array[ChannelAttempt] | yes | Submit/query attempts, newest last. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |
| `version` | integer | yes | Aggregate version after latest mutation. |

### ChannelStatement

Daily statements are generated only by an operations-triggered endpoint in Wave A;
there is no scheduler contract.

| Field | Type | Required | Description |
|---|---|---|---|
| `channelStatementId` | string | yes | Statement ID (`chs-<uuid-v7>`). |
| `channel` | enum | yes | SIM channel. |
| `statementDate` | string | yes | UTC calendar date, `YYYY-MM-DD`. |
| `currency` | string | yes | ISO-4217 currency. |
| `seedVersion` | string | yes | Statement seed version. |
| `status` | enum | yes | Statement status. |
| `periodStartAt` | RFC3339 UTC | yes | Inclusive UTC day boundary. |
| `periodEndAt` | RFC3339 UTC | yes | Exclusive UTC day boundary. |
| `generatedAt` | RFC3339 UTC | yes | Generation timestamp. |
| `frozenAt` | RFC3339 UTC | no | Freeze timestamp. |
| `lineCount` | integer | yes | Number of statement lines. |
| `grossPaymentAmount` | Money | yes | Sum of payment lines. |
| `grossRefundAmount` | Money | yes | Sum of refund lines as positive Money. |
| `feeAmount` | Money | yes | Sum of fees as Money. |
| `statementHash` | string | yes | Hash of canonical statement content. |
| `lines` | array[StatementLine] | yes | Statement lines. List endpoints may omit or truncate lines and include `lineCount`. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |
| `version` | integer | yes | Aggregate version after latest mutation. |

`StatementLine` fields:

| Field | Type | Required | Description |
|---|---|---|---|
| `statementLineId` | string | yes | Statement line ID (`csl-<uuid-v7>`). |
| `lineType` | enum | yes | `PAYMENT`, `REFUND`, `FEE`, or `ADJUSTMENT`. |
| `lineStatus` | enum | yes | Statement line status. |
| `channelOrderId` | string | no | Referenced channel order for payment/fee lines. |
| `channelRefundId` | string | no | Referenced channel refund for refund lines. |
| `paymentIntentId` | string | no | Payment reference if known. |
| `refundId` | string | no | Refund reference if known. |
| `channelTransactionId` | string | no | SIM payment transaction reference. |
| `channelRefundTransactionId` | string | no | SIM refund transaction reference. |
| `expectedAmount` | Money | yes | Platform/request amount expected by Payment Channel. |
| `actualAmount` | Money | yes | Amount observed in deterministic SIM statement. |
| `feeAmount` | Money | no | Fee for this line, if applicable. |
| `occurredAt` | RFC3339 UTC | yes | SIM business occurrence time. |
| `faultInjected` | boolean | yes | Whether this line was produced/modified by seed injection. |
| `faultSeedRef` | string | no | Seed reference causing the discrepancy. |
| `evidenceHash` | string | yes | Hash of canonical line evidence. |

### ReconciliationDiscrepancy

| Field | Type | Required | Description |
|---|---|---|---|
| `discrepancyId` | string | yes | Discrepancy ID (`pcd-<uuid-v7>`). |
| `channelStatementId` | string | yes | Statement that exposed the discrepancy. |
| `statementLineId` | string | no | Statement line, if line-bound. |
| `channelOrderId` | string | no | Related channel order, if known. |
| `channelRefundId` | string | no | Related channel refund, if known. |
| `financeReconciliationCaseId` | string | no | Finance case linked through existing Finance Settlement contract. |
| `differenceType` | enum | yes | Difference classification. |
| `expectedAmount` | Money | yes | Expected platform/channel amount. |
| `actualAmount` | Money | yes | Actual statement/channel amount. |
| `status` | enum | yes | Discrepancy status. |
| `evidenceRef` | string | yes | Evidence reference or hash; no raw PII/documents. |
| `resolutionRef` | string | no | Resolution evidence/reference after terminal resolution. |
| `openedAt` | RFC3339 UTC | yes | Opening timestamp. |
| `resolvedAt` | RFC3339 UTC | no | Resolution/rejection timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |
| `version` | integer | yes | Aggregate version after latest mutation. |

## Command idempotency material

API header keys are used directly when an HTTP caller provides `Idempotency-Key`.
Internal command keys are folded from canonical material because some commands are
created from event handlers or deterministic operations.

| Command / endpoint | Idempotency source | Canonical folded material |
|---|---|---|
| Create channel order | API header direct | `paymentIntentId + businessRef + purpose + channel + amount.currency + amount.minorUnits + sourceCommandId` |
| Submit channel order | Internal folded | `channelOrderId + nextSubmitAttemptNo + requestFingerprint` |
| Query channel order | Internal folded | `channelOrderId + nextQueryAttemptNo` |
| Create channel refund | API header direct | `refundId + channelOrderId + originalChannelTransactionId + amount.currency + amount.minorUnits` |
| Submit channel refund | Internal folded | `channelRefundId + nextSubmitAttemptNo + requestFingerprint` |
| Query channel refund | Internal folded | `channelRefundId + nextQueryAttemptNo` |
| Generate statement | API header direct | `channel + statementDate + currency + seedVersion` |
| Freeze statement | API header direct | `channelStatementId + statementHash` |
| Resolve discrepancy | API header direct | `discrepancyId + resolutionStatus + resolutionRef + expectedVersion` |

Replays with identical material return the original response. Reusing an API
`Idempotency-Key` with different body material returns `IDEMPOTENCY_KEY_REUSED`
(422). Reusing folded internal material with different semantic inputs returns
`CONFLICT` or `DOMAIN_RULE_VIOLATION` according to the invariant violated.

## Endpoints

### Create Channel Order

**POST** `/api/v1/channel-orders`

**Idempotency:** REQUIRED (`Idempotency-Key` header, UUID-v7 shape).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `paymentIntentId` | string | yes | Payment intent to hand off. |
| `businessRef` | string | yes | Payment business reference snapshot. |
| `purpose` | string | yes | Payment purpose snapshot. |
| `channel` | enum | yes | SIM channel. |
| `amount` | Money | yes | Amount to submit; `minorUnits` must be positive. |
| `sourceCommandId` | string | yes | Payment command ID in `cmd-<uuid-v7>` form. |
| `correlationId` | string | yes | Correlation ID in `corr-<uuid-v7>` form. |
| `faultSeed` | FaultSeed | no | Optional deterministic SIM seed for non-production scenarios. |

**Response (201):** `ChannelOrder` resource with `status=CREATED`.

**Domain effects:** emits `ChannelOrderCreated`.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Submit Channel Order

**POST** `/api/v1/channel-orders/{channelOrderId}/submit`

Internal endpoint used by Payment Channel application services or authorized
operations. It does not create a second external intent for a terminal order.

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedVersion` | integer | yes | Optimistic concurrency version. |
| `requestFingerprint` | string | yes | Fingerprint from the creation material. |

**Response (200):** `ChannelOrder` resource after applying the deterministic SIM
response (`SUBMITTED`, `ACCEPTED`, `SUCCEEDED`, `FAILED`, or `MISSED`).

**Domain effects:** emits one or more of `ChannelOrderSubmitted`,
`ChannelOrderAccepted`, `ChannelOrderSucceeded`, `ChannelOrderFailed`, or
`ChannelOrderMissed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Query Channel Order

**POST** `/api/v1/channel-orders/{channelOrderId}/query`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedVersion` | integer | yes | Optimistic concurrency version unless the order is terminal. |
| `queryReasonCode` | string | yes | Stable reason such as `OPS_RECHECK`, `PAYMENT_TIMEOUT`, or `STATEMENT_EVIDENCE`. |

**Response (200):** `ChannelOrder` resource. Terminal resources are restful: the
same terminal representation is returned without creating a new external side
effect.

**Domain effects:** emits `ChannelOrderQueryRecorded`; may emit
`ChannelOrderRecoveryDetected` when deterministic query evidence finds a late
success after `MISSED`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Channel Order

**GET** `/api/v1/channel-orders/{channelOrderId}`

**Response (200):** `ChannelOrder` resource.

**Error codes:** `NOT_FOUND`

### Create Channel Refund

**POST** `/api/v1/channel-refunds`

**Idempotency:** REQUIRED (`Idempotency-Key` header, UUID-v7 shape).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `refundId` | string | yes | Payment refund reference. |
| `paymentIntentId` | string | yes | Original payment intent. |
| `channelOrderId` | string | yes | Successful original channel order. |
| `originalChannelTransactionId` | string | yes | Original SIM payment transaction. |
| `channel` | enum | yes | Must equal the original order channel. |
| `amount` | Money | yes | Positive refund amount; cumulative successful and in-flight refunds must not exceed original captured amount. |
| `refundReasonCode` | string | yes | Stable refund reason code. |
| `sourceCommandId` | string | yes | Payment refund command ID in `cmd-<uuid-v7>` form. |
| `correlationId` | string | yes | Correlation ID in `corr-<uuid-v7>` form. |
| `faultSeed` | FaultSeed | no | Optional deterministic SIM seed for non-production scenarios. |

**Response (201):** `ChannelRefund` resource with `status=CREATED`.

**Domain effects:** emits `ChannelRefundCreated`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Submit Channel Refund

**POST** `/api/v1/channel-refunds/{channelRefundId}/submit`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedVersion` | integer | yes | Optimistic concurrency version. |
| `requestFingerprint` | string | yes | Fingerprint from the creation material. |

**Response (200):** `ChannelRefund` resource after applying the deterministic SIM
response.

**Domain effects:** emits one or more of `ChannelRefundSubmitted`,
`ChannelRefundSucceeded`, `ChannelRefundFailed`, or `ChannelRefundMissed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Query Channel Refund

**POST** `/api/v1/channel-refunds/{channelRefundId}/query`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `expectedVersion` | integer | yes | Optimistic concurrency version unless the refund is terminal. |
| `queryReasonCode` | string | yes | Stable reason such as `OPS_RECHECK`, `REFUND_TIMEOUT`, or `STATEMENT_EVIDENCE`. |

**Response (200):** `ChannelRefund` resource. Terminal resources are restful and
MUST NOT create a new refund side effect.

**Domain effects:** emits `ChannelRefundQueryRecorded`; may emit
`ChannelRefundRecoveryDetected` when deterministic query evidence finds a late
success after `MISSED`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Channel Refund

**GET** `/api/v1/channel-refunds/{channelRefundId}`

**Response (200):** `ChannelRefund` resource.

**Error codes:** `NOT_FOUND`

### Generate Channel Statement

**POST** `/api/v1/channel-statements/generate`

Operations-triggered endpoint. RULING: Wave A does not define a scheduler.

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `channel` | enum | yes | SIM channel. |
| `statementDate` | string | yes | UTC statement date, `YYYY-MM-DD`. |
| `currency` | string | yes | ISO-4217 currency. |
| `seedVersion` | string | yes | Deterministic statement seed version. |
| `scenarioCodes` | array[enum] | no | Optional scenario filter for operations-driven replay. |
| `operatorRef` | string | yes | Operator or system actor reference. |
| `reasonCode` | string | yes | Stable reason for generation/replay. |

**Response (201):** `ChannelStatement` resource with `status=GENERATED`.

**Domain effects:** emits `ChannelStatementGenerated`. Same
`channel + statementDate + currency + seedVersion` produces the same canonical
`statementHash` for the same input event stream and seed.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Freeze Channel Statement

**POST** `/api/v1/channel-statements/{channelStatementId}/freeze`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `statementHash` | string | yes | Hash expected by the caller. |
| `expectedVersion` | integer | yes | Optimistic concurrency version. |
| `operatorRef` | string | yes | Operator or system actor reference. |
| `reasonCode` | string | yes | Stable reason for freezing. |

**Response (200):** `ChannelStatement` resource with `status=FROZEN`.

**Domain effects:** emits `ChannelStatementFrozen`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Channel Statement

**GET** `/api/v1/channel-statements/{channelStatementId}`

**Response (200):** `ChannelStatement` resource.

**Error codes:** `NOT_FOUND`

### List Channel Statements

**GET** `/api/v1/channel-statements?channel={channel}&statementDate={YYYY-MM-DD}&currency={currency}&limit=20&offset=0`

At least one of `channelStatementId`, `channel`, or `statementDate` filter must
be present; broad unfiltered listing is not part of Wave A.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `channel` | enum | no | SIM channel filter. |
| `statementDate` | string | no | UTC date filter. |
| `currency` | string | no | Currency filter. |
| `status` | enum | no | Statement status filter. |
| `limit` | integer | no | Default 20, max 100. |
| `offset` | integer | no | Default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`; each item is a `ChannelStatement` summary and MAY omit `lines`.

**Error codes:** `VALIDATION_FAILED`

### Open Reconciliation Discrepancy

**POST** `/api/v1/channel-discrepancies`

Internal endpoint used by statement matching or Finance feedback to record a
channel-side discrepancy. It does not mutate Payment or Finance aggregates.

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `channelStatementId` | string | yes | Statement that exposed the discrepancy. |
| `statementLineId` | string | no | Statement line, if line-bound. |
| `channelOrderId` | string | no | Related channel order, if known. |
| `channelRefundId` | string | no | Related channel refund, if known. |
| `financeReconciliationCaseId` | string | no | Existing Finance case to attach. |
| `differenceType` | enum | yes | Difference classification. |
| `expectedAmount` | Money | yes | Expected amount. |
| `actualAmount` | Money | yes | Actual amount. |
| `evidenceRef` | string | yes | Evidence reference/hash; no raw PII/documents. |

**Response (201):** `ReconciliationDiscrepancy` resource.

**Domain effects:** emits `ReconciliationDiscrepancyOpened`; may emit
`ReconciliationDiscrepancyLinkedToFinanceCase` when a Finance case is supplied.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Resolve Reconciliation Discrepancy

**POST** `/api/v1/channel-discrepancies/{discrepancyId}/resolve`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `resolutionStatus` | enum | yes | `RESOLVED` or `REJECTED`. |
| `resolutionRef` | string | yes | Resolution evidence or manual action reference. |
| `operatorRef` | string | yes | Operator or system actor reference. |
| `reasonCode` | string | yes | Stable resolution reason. |
| `expectedVersion` | integer | yes | Optimistic concurrency version. |

**Response (200):** `ReconciliationDiscrepancy` resource in terminal status.

**Domain effects:** emits `ReconciliationDiscrepancyResolved`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Reconciliation Discrepancy

**GET** `/api/v1/channel-discrepancies/{discrepancyId}`

**Response (200):** `ReconciliationDiscrepancy` resource.

**Error codes:** `NOT_FOUND`

### List Reconciliation Discrepancies

**GET** `/api/v1/channel-discrepancies?channelStatementId={channelStatementId}&status={status}&limit=20&offset=0`

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `channelStatementId` | string | no | Statement filter. |
| `status` | enum | no | Discrepancy status filter. |
| `differenceType` | enum | no | Difference type filter. |
| `limit` | integer | no | Default 20, max 100. |
| `offset` | integer | no | Default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`.

**Error codes:** `VALIDATION_FAILED`

## SIM gateway deterministic behavior

| Channel | Payment/capture | Original-route refund | Partial refund | Status query | Daily statement | Seedable scenarios |
|---|---|---|---|---|---|---|
| `ALIPAY_SIM` | yes | yes | yes | yes | yes | `MISSED_ORDER`, `AMOUNT_MISMATCH`, `REFUND_LAG` |
| `WECHAT_SIM` | yes | yes | yes | yes | yes | `MISSED_ORDER`, `STATUS_MISMATCH`, `DUPLICATE_LINE` |
| `UNIONPAY_SIM` | yes | yes | yes | yes | yes | `AMOUNT_MISMATCH`, `MISSED_REFUND`, `STATEMENT_DELAY` |

Rules:

- The gateway is an in-process/test-fixture adapter and MUST NOT open real
  external HTTP/TCP connections or read real payment credentials.
- `FaultSeed(channel, businessRef, statementDate, scenarioCode, seedVersion)`
  determines synchronous response, query response, missed windows, and statement
  line injection.
- Amount mismatch seed changes statement `actualAmount`; it MUST NOT rewrite
  `ChannelOrder.amount` or `ChannelRefund.amount`.
- Missed order/refund seed records a submitted side effect and therefore MUST NOT
  allow blind recreation under a different idempotency material.
