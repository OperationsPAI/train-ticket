# Disruption Recovery — Events & Commands

Last updated: 2026-07-09

## Scope and activation-wave rulings

This contract lists the Disruption Recovery events active in the ADR-0002 third
activation wave. It is bounded by `docs/02-domains/disruption-recovery.md` and
by the activation rulings in `docs/08-contracts/api/disruption-recovery.md`.

Activation-wave rulings:

- The only signal source is the operations HTTP command
  `POST /api/v1/disruptions`. It represents Customer Service/Admin manual rows
  and carries a normalized `disruptionType`, `scheduledServiceRef` and/or
  `segmentRef`, `evidence`, and explicit `affectedOrderIds`.
- Automatic `segmentRef` to orders fan-out is deferred because Journey Order has
  no by-segment query contract. Service Plan, Provider Integration,
  Fulfillment, and Transfer Management signal events are also deferred; Transfer
  belongs to wave 18.
- The report opens or merges an `Incident`; merge key is
  `(scheduledServiceRef, serviceDate)` when `scheduledServiceRef` is present.
  `ServiceAlert` is event-only in this wave: `ServiceAlertPublished` is
  published, but the ServiceAlert read model is deferred.
- One `RecoveryCase` is opened per explicit `affectedOrderId`. Event payload
  statuses use the exact 10-state domain machine: `OPENED`, `ASSESSING_IMPACT`,
  `OPTIONS_GENERATED`, `AWAITING_USER_CHOICE`, `EXECUTING_RECOVERY`,
  `MANUAL_REVIEW`, `RECOVERED`, `DECLINED`, `FAILED`, `CLOSED`.
- `RecoveryOptionSet` supports `WAIT`, `REFUND`, `COMPENSATION`, and `MANUAL`.
  `REACCOMMODATION` is deferred until wave 18 after Transfer Management.
- Automatic rules may select only `WAIT`. `WAIT` completes as `RECOVERED` with
  no downstream command. `REFUND` calls the existing Post Sales HTTP contract
  using a deterministic idempotency key and converges on `PostSalesApplied`.
  `COMPENSATION` calls Wallet / Promotion `POST /api/v1/benefits` with
  `issuanceSource=DISRUPTION_COMP`. `MANUAL` moves the case to manual review.
- Consumers of Disruption Recovery events are deferred for Notification and
  Reporting in this wave. The only active inbound subscription is
  `events:post-sales` `PostSalesApplied` for REFUND execution convergence.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE, and
all timestamps are RFC3339 UTC. Envelope fields, including optional trace context
propagation, follow `docs/08-contracts/messaging.md` and
`docs/08-contracts/shared-primitives.md`. Monetary values use `Money` as
`{currency, minorUnits}`.

## Event identity and idempotency

Disruption Recovery producers MUST assign deterministic event IDs per aggregate
transition. The event ID seed is:

```
disruption-recovery:<eventType>:<aggregateId>:<aggregateVersion>
```

where `aggregateId` is `incidentId` for incident and alert events,
`disruptionId` for `DisruptionReported`, and `caseId` for recovery-case events.
If the same command or consumed upstream event is replayed and no new transition
is applied, no new event is emitted. Consumers still deduplicate by envelope
`eventId`.

Downstream HTTP commands use deterministic idempotency keys:

| Option type | Downstream command | Idempotency-key seed |
|---|---|---|
| `REFUND` | `POST /api/v1/post-sales-cases` with `caseType=REFUND` | `disruption-recovery:refund:<caseId>:<optionId>` |
| `COMPENSATION` | `POST /api/v1/benefits` with `issuanceSource=DISRUPTION_COMP` | `disruption-recovery:compensation:<caseId>:<optionId>` |

## Common payload objects

### Evidence

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceRef` | string | yes | Reference to the customer-service/admin evidence record. |
| `sourceSystem` | enum | yes | `CUSTOMER_SERVICE` or `ADMIN`; all other sources are deferred. |
| `sourceRecordId` | string | yes | Upstream manual-row identifier. |
| `summary` | string | yes | Operational summary; must not include unmasked documents or sensitive personal data. |
| `occurredAt` | RFC3339 UTC | no | Observation time if known. |

### ActorRef

| Field | Type | Required | Description |
|---|---|---|---|
| `actorType` | enum | yes | `USER`, `CUSTOMER_SERVICE`, `OPERATIONS`, or `SYSTEM`. |
| `actorId` | string | yes | Account, operator, or service actor reference. |

### AffectedScope

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | string | yes | Affected `ord-<uuid>`. |
| `scheduledServiceRef` | string | no | Scheduled service reference from the report. |
| `segmentRef` | string | no | Segment reference from the report. |
| `serviceDate` | string | yes | ISO `YYYY-MM-DD` service date. |
| `disruptionType` | enum | yes | Normalized disruption type. |
| `evidenceRef` | string | yes | Supporting evidence reference. |

### RecoveryOption

| Field | Type | Required | Description |
|---|---|---|---|
| `optionId` | string | yes | Option ID (`rop-<uuid>`). |
| `optionType` | enum | yes | `WAIT`, `REFUND`, `COMPENSATION`, or `MANUAL`. |
| `title` | string | yes | Display title. |
| `description` | string | yes | Explanation; must not include sensitive personal data. |
| `executionTarget` | enum | yes | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, or `MANUAL_QUEUE`. |
| `refund` | object | for `REFUND` | `{reasonCode, scope, waiverRef?}` for the Post Sales REFUND case. |
| `compensation` | object | for `COMPENSATION` | `{amount: Money, benefitType, balanceType, validUntil, reasonCode}` for Wallet / Promotion. |
| `manualReason` | string | for `MANUAL` | Reason the case requires manual review. |
| `expiresAt` | RFC3339 UTC | no | Option-specific expiry. |

## Published Events

### DisruptionReported

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: reporting |
| **Trigger** | Operations report accepted from `POST /api/v1/disruptions`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `disruptionId` | string | yes | Disruption report ID (`drp-<uuid>`). |
| `disruptionType` | enum | yes | Normalized type from the HTTP contract enum. |
| `scheduledServiceRef` | string | no | Scheduled service reference. |
| `segmentRef` | string | no | Segment reference. |
| `serviceDate` | string | yes | ISO `YYYY-MM-DD` service date. |
| `evidence` | object | yes | Evidence object. |
| `affectedOrderIds` | array[string] | yes | Explicit affected orders supplied by operations. |
| `reportedBy` | object | yes | Reporting actor. |
| `reportedAt` | RFC3339 UTC | yes | Report timestamp. |

### IncidentOpened

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | Accepted report opens a new incident instead of merging into an existing one. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `incidentId` | string | yes | Incident ID (`inc-<uuid>`). |
| `disruptionId` | string | yes | Report that opened the incident. |
| `disruptionType` | enum | yes | Dominant normalized disruption type. |
| `scheduledServiceRef` | string | no | Service reference used for merge when present. |
| `segmentRefs` | array[string] | no | Segment refs in the incident scope. |
| `serviceDate` | string | yes | ISO `YYYY-MM-DD` service date. |
| `evidenceRefs` | array[string] | yes | Evidence references attached at open. |
| `affectedOrderIds` | array[string] | yes | Explicit affected orders at open. |
| `status` | enum | yes | `CONFIRMED` in this wave. |
| `openedAt` | RFC3339 UTC | yes | Open timestamp. |

### RecoveryCaseOpened

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | A report opens a recovery case for one explicit `affectedOrderId`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID (`rcv-<uuid>`). |
| `incidentId` | string | yes | Parent incident. |
| `disruptionId` | string | yes | Source report. |
| `journeyOrderId` | string | yes | Affected order (`ord-<uuid>`). |
| `affectedScope` | object | yes | Affected scope object. |
| `openedAt` | RFC3339 UTC | yes | Case open timestamp. |
| `status` | enum | yes | `OPENED`. |

### RecoveryOptionsGenerated

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | `GenerateRecoveryOptions` creates this wave's option set for a case. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionSetId` | string | yes | Option set ID (`ros-<uuid>`). |
| `options` | array[RecoveryOption] | yes | Options; active enum is `WAIT`, `REFUND`, `COMPENSATION`, or `MANUAL`. |
| `requiresUserChoice` | boolean | yes | `false` only for auto-selectable `WAIT`. |
| `generatedAt` | RFC3339 UTC | yes | Generation timestamp. |
| `expiresAt` | RFC3339 UTC | no | Choice deadline. |
| `status` | enum | yes | `OPTIONS_GENERATED` or `AWAITING_USER_CHOICE` after generation flow. |

### RecoveryOptionSelected

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | Automatic `WAIT` selection or user/customer-service `select-option` command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionSetId` | string | yes | Option set containing the selected option. |
| `optionId` | string | yes | Selected option. |
| `optionType` | enum | yes | `WAIT`, `REFUND`, `COMPENSATION`, or `MANUAL`. |
| `selectedBy` | object | yes | Actor that selected the option; `SYSTEM` is allowed only for `WAIT`. |
| `selectedAt` | RFC3339 UTC | yes | Selection timestamp. |
| `status` | enum | yes | `EXECUTING_RECOVERY` for executable options, or `MANUAL_REVIEW` for `MANUAL`. |

### RecoveryExecutionStarted

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: reporting |
| **Trigger** | Selected option starts local execution or a downstream HTTP command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionId` | string | yes | Selected option. |
| `optionType` | enum | yes | Selected option type. |
| `executionId` | string | yes | Execution ID (`rex-<uuid>`). |
| `executionTarget` | enum | yes | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, or `MANUAL_QUEUE`. |
| `idempotencyKey` | string | no | Deterministic downstream idempotency key for `REFUND` or `COMPENSATION`. |
| `downstreamRequest` | object | no | Sanitized downstream request summary. For `REFUND`, includes `caseType=REFUND`; for `COMPENSATION`, includes `issuanceSource=DISRUPTION_COMP`. |
| `startedAt` | RFC3339 UTC | yes | Execution start timestamp. |
| `status` | enum | yes | `EXECUTING_RECOVERY` or `MANUAL_REVIEW`. |

### RecoveryCompleted

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | `WAIT` completes locally, Wallet / Promotion benefit issuance succeeds, or consumed `PostSalesApplied` converges a REFUND execution. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionId` | string | yes | Selected option. |
| `optionType` | enum | yes | Completed option type. |
| `executionId` | string | yes | Execution ID. |
| `completedAt` | RFC3339 UTC | yes | Completion timestamp. |
| `externalRef` | string | no | Post Sales case ID, Wallet benefit ID, or manual reference when applicable. |
| `status` | enum | yes | `RECOVERED`. |

### RecoveryFailed

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | A selected option fails and cannot automatically converge. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionId` | string | no | Selected option, if any. |
| `executionId` | string | no | Execution that failed, if any. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `reason` | string | yes | Failure reason; must not include sensitive personal data. |
| `nextStatus` | enum | yes | `OPTIONS_GENERATED`, `MANUAL_REVIEW`, or `FAILED`. |

### RecoveryCaseClosed

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: reporting |
| **Trigger** | `CloseRecoveryCase` archives a terminal or manually resolved case. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `previousStatus` | enum | yes | `RECOVERED`, `DECLINED`, `FAILED`, or `MANUAL_REVIEW` when manually closed. |
| `closedBy` | object | yes | Closing actor. |
| `closeReason` | string | yes | Closure reason; must not include sensitive personal data. |
| `closedAt` | RFC3339 UTC | yes | Closure timestamp. |
| `status` | enum | yes | `CLOSED`. |

### ServiceAlertPublished

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | deferred: notification, reporting |
| **Trigger** | Incident alert fact is published for affected users, customer service, or operations. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `serviceAlertId` | string | yes | Service alert ID (`sal-<uuid>`). |
| `incidentId` | string | yes | Parent incident. |
| `disruptionType` | enum | yes | Normalized disruption type. |
| `scheduledServiceRef` | string | no | Scheduled service reference. |
| `segmentRef` | string | no | Segment reference. |
| `serviceDate` | string | yes | ISO `YYYY-MM-DD` service date. |
| `audience` | enum | yes | `AFFECTED_ORDERS`, `CUSTOMER_SERVICE`, or `OPERATIONS`. |
| `affectedOrderIds` | array[string] | no | Explicit affected orders when audience is `AFFECTED_ORDERS`. |
| `messageSummary` | string | yes | Alert summary; must not include unmasked documents or sensitive personal data. |
| `publishedAt` | RFC3339 UTC | yes | Publish timestamp. |

## Accepted Commands

| Command | Sender / Trigger | Produced event |
|---|---|---|
| `ReportDisruption` | Operations HTTP `POST /api/v1/disruptions` | `DisruptionReported` |
| `OpenIncident` | Application flow after report, or merge lookup miss | `IncidentOpened` |
| `OpenRecoveryCase` | Application flow for each explicit `affectedOrderId` | `RecoveryCaseOpened` |
| `GenerateRecoveryOptions` | Application flow after impact assessment | `RecoveryOptionsGenerated` |
| `SelectRecoveryOption` | Automatic `WAIT`, or HTTP `POST /api/v1/recovery-cases/{caseId}/select-option` | `RecoveryOptionSelected` |
| `ApplyRecoveryDecision` | Internal execution start after selection | `RecoveryExecutionStarted` |
| `RecordRecoveryExecutionResult` | Local wait completion, Wallet issuance result, or consumed `PostSalesApplied` | `RecoveryCompleted` or `RecoveryFailed` |
| `CloseRecoveryCase` | HTTP `POST /api/v1/recovery-cases/{caseId}/close` or manual closure | `RecoveryCaseClosed` |
| `PublishServiceAlert` | Application flow after incident open/merge | `ServiceAlertPublished` |

## Consumed upstream events

| Upstream stream | Event type | Purpose |
|---|---|---|
| `events:post-sales` | `PostSalesApplied` | Converge a selected `REFUND` option when the downstream Post Sales case reaches `APPLIED`. |

No other upstream event subscription is active in this wave. Service Plan,
Provider Integration, Fulfillment, and Transfer Management disruption sources
are deferred.

## Deferred downstream touchpoints

| Downstream context | Deferred events | Purpose when activated |
|---|---|---|
| Notification | `ServiceAlertPublished`, `RecoveryOptionsGenerated`, `RecoveryOptionSelected`, `RecoveryCompleted`, `RecoveryFailed` | User-facing alert, option, decision, and progress notifications. |
| Reporting | all Disruption Recovery events | Disruption metrics, waiver/compensation cost attribution, and operational read models. |
| Journey Order | recovery summary events | Order-detail disruption and recovery display. |
