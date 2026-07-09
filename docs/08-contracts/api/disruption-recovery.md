# Disruption Recovery — HTTP API

Last updated: 2026-07-09

## Overview

Disruption Recovery receives operational disruption reports, merges them into
incidents, opens one recovery case per affected journey order, generates the
activation-wave recovery options, and orchestrates selected executions. This
contract is scoped to the ADR-0002 third activation wave and is bounded by
`docs/02-domains/disruption-recovery.md`.

Activation-wave rulings:

- The only disruption signal source in this wave is **operations reporting** via
  `POST /api/v1/disruptions`. The request represents the Customer
  Service/Admin manual rows from the upstream table and MUST carry
  `disruptionType`, `scheduledServiceRef` and/or `segmentRef`, `serviceDate`,
  `evidence`, and explicit `affectedOrderIds`. Automatic `segmentRef` to order
  fan-out is deferred because Journey Order has no by-segment query contract.
- Service Plan, Provider Integration, Fulfillment, and Transfer Management event
  sources are deferred. Most of those events are not in production today;
  Transfer Management is wave 18.
- An `Incident` is opened or merged by the same report. This wave merges by
  `(scheduledServiceRef, serviceDate)` when `scheduledServiceRef` is present;
  otherwise the report opens a distinct incident for its supplied scope.
- `ServiceAlert` is event-only in this wave. Disruption Recovery publishes
  `ServiceAlertPublished`; the ServiceAlert read model is deferred.
- The `RecoveryCase` state machine is exactly the 10-state machine from the
  domain document (`OPENED` through `CLOSED`) and follows the transition table
  below. One `RecoveryCase` is opened for each `affectedOrderId` supplied in the
  report.
- `RecoveryOptionSet` supports four option types in this wave: `WAIT`, `REFUND`,
  `COMPENSATION`, and `MANUAL`. `REACCOMMODATION` is deferred until wave 18 after
  Transfer Management activation.
- Automatic selection rules may only select `WAIT`, which completes immediately
  as `RECOVERED` without a downstream call. `REFUND`, `COMPENSATION`, and
  `MANUAL` move the case to `AWAITING_USER_CHOICE` and require
  `POST /api/v1/recovery-cases/{caseId}/select-option` by the user or customer
  service.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, pagination conventions, and Money
(`{currency, minorUnits}`). All timestamps are RFC3339 UTC. JSON fields are
camelCase and enum values are SCREAMING_SNAKE_CASE.

## Common enums

| Enum | Values |
|---|---|
| `disruptionType` | `SERVICE_DELAY`, `SERVICE_CANCELLED`, `SERVICE_SUSPENDED`, `SAILING_SUSPENDED`, `ROAD_CLOSED`, `WEATHER`, `OPERATION_RESTRICTION`, `SUPPLIER_FAILURE`, `DRIVER_CANCELLED`, `DISPATCH_FAILED`, `STOP_CHANGED`, `PORT_CALL_CHANGED`, `BATCH_SYSTEM_EVENT`, `CONNECTION_MISSED` |
| `incidentStatus` | `DETECTED`, `CONFIRMED`, `BATCH_PROCESSING`, `MONITORING`, `RESOLVED`, `CLOSED` |
| `recoveryCaseStatus` | `OPENED`, `ASSESSING_IMPACT`, `OPTIONS_GENERATED`, `AWAITING_USER_CHOICE`, `EXECUTING_RECOVERY`, `MANUAL_REVIEW`, `RECOVERED`, `DECLINED`, `FAILED`, `CLOSED` |
| `recoveryOptionType` | `WAIT`, `REFUND`, `COMPENSATION`, `MANUAL` |
| `actorType` | `USER`, `CUSTOMER_SERVICE`, `OPERATIONS`, `SYSTEM` |
| `executionTarget` | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, `MANUAL_QUEUE` |

`REACCOMMODATION` is intentionally absent from the active option-type enum in
this wave. It remains a domain capability but is deferred until wave 18.

## RecoveryCase state machine

The wire status enum is the 10-state domain state machine:

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `OPENED` | A supplied affected order was confirmed as impacted by an incident/disruption report. | `ASSESSING_IMPACT`, `MANUAL_REVIEW` |
| `ASSESSING_IMPACT` | Disruption Recovery is binding affected scope, responsibility, protection, and waiver candidates. | `OPTIONS_GENERATED`, `MANUAL_REVIEW` |
| `OPTIONS_GENERATED` | A recovery option set exists and can either auto-select or wait for choice. | `EXECUTING_RECOVERY`, `AWAITING_USER_CHOICE`, `MANUAL_REVIEW` |
| `AWAITING_USER_CHOICE` | User or customer service must choose an unexpired option. | `EXECUTING_RECOVERY`, `MANUAL_REVIEW`, `DECLINED` |
| `EXECUTING_RECOVERY` | The selected option is executing locally or in a downstream context. | `RECOVERED`, `OPTIONS_GENERATED`, `MANUAL_REVIEW`, `FAILED` |
| `MANUAL_REVIEW` | Automatic decision or execution cannot proceed safely. | `EXECUTING_RECOVERY`, `RECOVERED`, `FAILED`, `CLOSED` |
| `RECOVERED` | Recovery completed by wait, refund, compensation, or an explicit manual outcome. | `CLOSED` |
| `DECLINED` | User declined available recovery or chose to self-handle. | `CLOSED` |
| `FAILED` | Execution failed and no automatic recovery path remains. | `CLOSED` |
| `CLOSED` | Case is archived; no further execution occurs on this case. | - |

Key transitions from the domain document are enforced with this wave's narrower
option set:

| Current status | Trigger | Target status | Rule |
|---|---|---|---|
| `OPENED` | `AssessRecoveryImpact` | `ASSESSING_IMPACT` | Case has `affectedScope` and source `evidenceRef`. |
| `ASSESSING_IMPACT` | `RecoveryImpactAssessed` | `OPTIONS_GENERATED` | Responsibility, protection, and waiver candidates were assessed for the supplied order. |
| `OPTIONS_GENERATED` | Automatic `WAIT` rule | `EXECUTING_RECOVERY` then `RECOVERED` | Only `WAIT` may be auto-selected; no downstream call is made. |
| `OPTIONS_GENERATED` | Non-`WAIT` option set generated | `AWAITING_USER_CHOICE` | `REFUND`, `COMPENSATION`, and `MANUAL` require user or customer-service selection. |
| `AWAITING_USER_CHOICE` | `RecoveryOptionSelected` | `EXECUTING_RECOVERY` | Option exists, is unexpired, belongs to the current option set, and actor is authorized. |
| `EXECUTING_RECOVERY` | Downstream execution succeeded or `WAIT` completed | `RECOVERED` | Required downstream convergence has been observed. |
| `EXECUTING_RECOVERY` | Recoverable execution failure | `OPTIONS_GENERATED` or `MANUAL_REVIEW` | The service may refresh options or require manual review. |
| Any non-terminal status | High risk, rule conflict, invalid evidence, or selected `MANUAL` | `MANUAL_REVIEW` | Case is placed in the operations/customer-service manual queue. |
| `RECOVERED`, `DECLINED`, `FAILED` | `CloseRecoveryCase` | `CLOSED` | Closed cases are audit-only. |

## Resource representations

### DisruptionReport

| Field | Type | Required | Description |
|---|---|---|---|
| `disruptionId` | string | yes | Canonical disruption report ID (`drp-<uuid>`). |
| `disruptionType` | enum | yes | Normalized disruption type from the enum above. |
| `scheduledServiceRef` | string | no | Scheduled service reference for merge and impact scope. Required when `segmentRef` is absent. |
| `segmentRef` | string | no | Segment reference from the order/service plan. Required when `scheduledServiceRef` is absent. |
| `serviceDate` | string | yes | Service operating date in ISO `YYYY-MM-DD`; used with `scheduledServiceRef` for incident merge. |
| `evidence` | object | yes | Operational evidence object. See `Evidence`. |
| `affectedOrderIds` | array[string] | yes | Explicit affected journey order IDs (`ord-<uuid>`). Must be non-empty; no automatic segment-to-order fan-out in this wave. |
| `reportedBy` | object | yes | Reporting actor. See `ActorRef`. |
| `reportedAt` | RFC3339 UTC | yes | Report timestamp. |
| `incidentId` | string | yes | Opened or merged incident ID (`inc-<uuid>`). |

### Evidence

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceRef` | string | yes | Reference to the admin/customer-service evidence record or uploaded artifact. |
| `sourceSystem` | enum | yes | `CUSTOMER_SERVICE` or `ADMIN`. Other upstream source systems are deferred. |
| `sourceRecordId` | string | yes | Upstream manual-row identifier. |
| `summary` | string | yes | Operational summary; MUST NOT include unmasked documents or other sensitive personal data. |
| `occurredAt` | RFC3339 UTC | no | When the disruption was observed, if known. |

### ActorRef

| Field | Type | Required | Description |
|---|---|---|---|
| `actorType` | enum | yes | `USER`, `CUSTOMER_SERVICE`, `OPERATIONS`, or `SYSTEM`. |
| `actorId` | string | yes | Account, operator, or service actor reference. |

### Incident

| Field | Type | Required | Description |
|---|---|---|---|
| `incidentId` | string | yes | Incident ID (`inc-<uuid>`). |
| `status` | enum | yes | Incident status. Newly accepted reports produce `CONFIRMED` incidents in this wave. |
| `disruptionType` | enum | yes | Dominant normalized type for the incident. |
| `scheduledServiceRef` | string | no | Service reference used for merge when present. |
| `segmentRefs` | array[string] | no | Segment refs reported for this incident. |
| `serviceDate` | string | yes | Service operating date. |
| `evidenceRefs` | array[string] | yes | Evidence references that opened or merged into the incident. |
| `affectedOrderCount` | integer | yes | Count of distinct explicit affected orders accepted for this incident. |
| `openedAt` | RFC3339 UTC | yes | Incident open timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last merge or state-change timestamp. |

### RecoveryCase

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID (`rcv-<uuid>`). |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order (`ord-<uuid>`). |
| `affectedScope` | object | yes | Scope used for recovery. See `AffectedScope`. |
| `status` | enum | yes | Recovery case status. |
| `optionSet` | RecoveryOptionSet | no | Current generated option set when available. |
| `selectedOptionId` | string | no | Selected option ID when a choice has been made. |
| `execution` | object | no | Current or final execution tracking. See `RecoveryExecution`. |
| `openedAt` | RFC3339 UTC | yes | Open timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |

### AffectedScope

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | string | yes | Affected order ID. |
| `scheduledServiceRef` | string | no | Scheduled service reference copied from the report when supplied. |
| `segmentRef` | string | no | Segment reference copied from the report when supplied. |
| `serviceDate` | string | yes | Service operating date. |
| `disruptionType` | enum | yes | Normalized disruption type. |
| `evidenceRef` | string | yes | Evidence reference that supports opening the case. |

### RecoveryOptionSet

| Field | Type | Required | Description |
|---|---|---|---|
| `optionSetId` | string | yes | Option set ID (`ros-<uuid>`). |
| `caseId` | string | yes | Owning recovery case. |
| `options` | array[RecoveryOption] | yes | Active options. This wave supports `WAIT`, `REFUND`, `COMPENSATION`, and `MANUAL`. |
| `generatedAt` | RFC3339 UTC | yes | Generation timestamp. |
| `expiresAt` | RFC3339 UTC | no | Choice deadline when user choice is required. |
| `requiresUserChoice` | boolean | yes | `false` only when the generated set contains an auto-selected `WAIT` path. |

### RecoveryOption

| Field | Type | Required | Description |
|---|---|---|---|
| `optionId` | string | yes | Option ID (`rop-<uuid>`). |
| `optionType` | enum | yes | `WAIT`, `REFUND`, `COMPENSATION`, or `MANUAL`. |
| `title` | string | yes | User/customer-service display title. |
| `description` | string | yes | Explanation of the recovery path; must not include sensitive personal data. |
| `executionTarget` | enum | yes | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, or `MANUAL_QUEUE`. |
| `refund` | object | for `REFUND` | Refund execution parameters. See `RefundOption`. |
| `compensation` | object | for `COMPENSATION` | Wallet benefit execution parameters. See `CompensationOption`. |
| `manualReason` | string | for `MANUAL` | Reason the case should enter manual review. |
| `expiresAt` | RFC3339 UTC | no | Option-specific expiry. |

### RefundOption

| Field | Type | Required | Description |
|---|---|---|---|
| `reasonCode` | string | yes | Post Sales reason code, normally `DISRUPTION_REFUND`. |
| `scope` | object | yes | Post Sales scope object for the affected order/items/segments. |
| `waiverRef` | string | no | Frozen waiver policy snapshot reference when a waiver applies. |

Selecting this option starts execution by calling the existing Post Sales
contract: `POST /api/v1/post-sales-cases` with `caseType=REFUND`, the option's
`scope`, the option `reasonCode`, and a deterministic idempotency key seeded as
`disruption-recovery:refund:<caseId>:<optionId>`. Disruption Recovery then
tracks `PostSalesApplied` from `events:post-sales` to converge the case to
`RECOVERED`.

### CompensationOption

| Field | Type | Required | Description |
|---|---|---|---|
| `amount` | Money | yes | Wallet benefit amount using `{currency, minorUnits}`. |
| `benefitType` | enum | yes | Wallet / Promotion benefit type, normally `COMPENSATION_CREDIT`. |
| `balanceType` | enum | yes | Wallet / Promotion balance type, normally `PROMOTION_CREDIT`. |
| `validUntil` | RFC3339 UTC | yes | Benefit expiry. |
| `reasonCode` | string | yes | Wallet business reason code, normally `DISRUPTION_COMP`. |

Selecting this option starts execution by calling Wallet / Promotion
`POST /api/v1/benefits` with a deterministic idempotency key seeded as
`disruption-recovery:compensation:<caseId>:<optionId>` and
`issuanceSource=DISRUPTION_COMP`. `DISRUPTION_COMP` is an additive Wallet /
Promotion issuance-source enum value introduced by this activation wave.

### RecoveryExecution

| Field | Type | Required | Description |
|---|---|---|---|
| `executionId` | string | yes | Execution tracking ID (`rex-<uuid>`). |
| `target` | enum | yes | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, or `MANUAL_QUEUE`. |
| `idempotencyKey` | string | no | Deterministic downstream idempotency key when a downstream HTTP command is used. |
| `externalRef` | string | no | Downstream case/benefit/manual-queue reference. |
| `startedAt` | RFC3339 UTC | yes | Execution start timestamp. |
| `completedAt` | RFC3339 UTC | no | Completion timestamp. |
| `failureReason` | string | no | Failure reason; do not include unmasked documents or sensitive personal data. |

## Endpoints

### Report Disruption

**POST** `/api/v1/disruptions`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Replays with the same body
return the original response. Reusing the same key with a different body returns
`IDEMPOTENCY_KEY_REUSED`.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `disruptionType` | enum | yes | Normalized disruption type. |
| `scheduledServiceRef` | string | no | Scheduled service reference. Required when `segmentRef` is absent. |
| `segmentRef` | string | no | Segment reference. Required when `scheduledServiceRef` is absent. |
| `serviceDate` | string | yes | ISO `YYYY-MM-DD` operating date; used with `scheduledServiceRef` for incident merge. |
| `evidence` | object | yes | Evidence object with `sourceSystem` of `CUSTOMER_SERVICE` or `ADMIN`. |
| `affectedOrderIds` | array[string] | yes | Explicit affected `ord-<uuid>` IDs. Must be non-empty. |
| `reportedBy` | object | yes | Reporting actor; normally `CUSTOMER_SERVICE` or `OPERATIONS`. |

**Response (202):**

| Field | Type | Description |
|---|---|---|
| `disruption` | DisruptionReport | Accepted report. |
| `incident` | Incident | Opened or merged incident. |
| `recoveryCases` | array[RecoveryCase] | One case per distinct `affectedOrderIds` entry. |

**Domain effects:** emits `DisruptionReported`, `IncidentOpened` when a new
incident is opened, `RecoveryCaseOpened` for each affected order, optionally
`RecoveryOptionsGenerated`, and `ServiceAlertPublished` for the incident alert
fact. A repeated report for an already known `(scheduledServiceRef, serviceDate)`
merges into the existing incident and does not duplicate active cases for the
same `(incidentId, journeyOrderId)`.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `DOMAIN_RULE_VIOLATION` is returned when neither `scheduledServiceRef` nor
  `segmentRef` is provided, evidence is missing, or `affectedOrderIds` is empty.
- `CONFLICT` is returned when the same idempotent operation attempts to open a
  duplicate active case with incompatible scope.

### Get Incident

**GET** `/api/v1/incidents/{incidentId}`

**Response (200):** `Incident` resource.

**Error codes:** `NOT_FOUND`

### Get Recovery Case

**GET** `/api/v1/recovery-cases/{caseId}`

**Response (200):** `RecoveryCase` resource.

**Error codes:** `NOT_FOUND`

### List Recovery Cases by Incident

**GET** `/api/v1/recovery-cases?incidentId={incidentId}&limit=20&offset=0`

`incidentId` is required for list queries. Broad unfiltered listing is not part
of this activation-wave API.

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `incidentId` | string | yes | Parent incident ID. |
| `status` | enum | no | Optional recovery-case status filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `RecoveryCase` resource.

**Error codes:** `VALIDATION_FAILED`

### Select Recovery Option

**POST** `/api/v1/recovery-cases/{caseId}/select-option`

**Idempotency:** REQUIRED (`Idempotency-Key` header). Replays with the same body
return the original response. Reusing the same key with a different body returns
`IDEMPOTENCY_KEY_REUSED`.

This endpoint is used by users or customer-service agents when a case is in
`AWAITING_USER_CHOICE`. `WAIT` normally auto-selects before reaching this state;
if exposed for an already waiting case, selecting `WAIT` still performs no
downstream call and completes as `RECOVERED`.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `optionId` | string | yes | Option being selected from the current `optionSet`. |
| `selectedBy` | object | yes | Selecting actor. `actorType` MUST be `USER` or `CUSTOMER_SERVICE`. |
| `selectionReason` | string | no | Optional reason. Must not include unmasked documents or other sensitive personal data. |

**Response (200):** `RecoveryCase` resource after selection and any synchronous
state advancement.

**Domain effects:** emits `RecoveryOptionSelected` and
`RecoveryExecutionStarted` for executable options. `WAIT` emits
`RecoveryCompleted` immediately and moves to `RECOVERED`. `REFUND` opens a Post
Sales `REFUND` case and waits for `PostSalesApplied`. `COMPENSATION` issues a
Wallet / Promotion benefit with `issuanceSource=DISRUPTION_COMP`. `MANUAL` moves
to `MANUAL_REVIEW` and is handled by operations/customer service outside this
public API.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `PRECONDITION_FAILED` is returned when the case is not in
  `AWAITING_USER_CHOICE`, the option set is expired, or the option has already
  been selected.

### Close Recovery Case

**POST** `/api/v1/recovery-cases/{caseId}/close`

**Idempotency:** REQUIRED (`Idempotency-Key` header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `closedBy` | object | yes | Closing actor; normally `CUSTOMER_SERVICE`, `OPERATIONS`, or `SYSTEM`. |
| `closeReason` | string | yes | Closure reason. Must not include unmasked documents or sensitive personal data. |

**Response (200):** `RecoveryCase` resource in `CLOSED` status.

**Domain effects:** emits `RecoveryCaseClosed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`

## Bus-only behavior

The following domain commands have no public HTTP endpoint in this activation
wave:

- `AssessRecoveryImpact` — runs after a report opens a case using the explicit
  order scope supplied by operations.
- `GenerateRecoveryOptions` — creates this wave's option set (`WAIT`, `REFUND`,
  `COMPENSATION`, `MANUAL`); `REACCOMMODATION` generation is deferred.
- `ApplyRecoveryDecision` / execution convergence — driven internally after
  selection and by consuming `PostSalesApplied` from `events:post-sales`.
- `PublishServiceAlert` — publishes the event-only `ServiceAlertPublished` fact;
  the ServiceAlert read model is deferred.

Deferred signal sources remain out of this wave: Service Plan, Provider
Integration, Fulfillment, and Transfer Management events do not open incidents
or cases until their activation waves.
