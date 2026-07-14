# Disruption Recovery — Events & Commands

Last updated: 2026-07-15

## Scope and activation-wave rulings

This contract lists the Disruption Recovery events active in the ADR-0002 third
activation wave. It is bounded by `docs/02-domains/disruption-recovery.md` and
by the activation rulings in `docs/08-contracts/api/disruption-recovery.md`.

Activation-wave rulings:

- Active signal sources are the operations/system HTTP command
  `POST /api/v1/disruptions`, Fulfillment `SegmentDelayed` /
  `SegmentCancelled`, and Provider Integration segment disruption events. HTTP
  reports carry a normalized `disruptionType`, `scheduledServiceRef` and/or
  `segmentRef`, `evidence`, and affected orders when supplied by the reporter.
- `segmentRef` to orders fan-out is active through the Journey Order event-bus
  projection built from `JourneyOrderCreated.segmentRefs`. A report or consumed
  segment signal with no explicit `affectedOrderIds` MUST resolve orders from
  this projection and then emit the standard `RecoveryCaseOpened` /
  `RecoveryOptionsGenerated` recovery events for each resolved order; no
  cross-service resolver HTTP call is introduced.
- The report opens or merges an `Incident`; merge key is
  `(scheduledServiceRef, serviceDate)` when `scheduledServiceRef` is present.
  `ServiceAlertPublished` is stored in the ServiceAlert read model and exposed by
  the Disruption Recovery API using the same payload shape as the event.
- One `RecoveryCase` is opened per explicit or segment-resolved `affectedOrderId`. Event payload
  statuses use the exact 10-state domain machine: `OPENED`, `ASSESSING_IMPACT`,
  `OPTIONS_GENERATED`, `AWAITING_USER_CHOICE`, `EXECUTING_RECOVERY`,
  `MANUAL_REVIEW`, `RECOVERED`, `DECLINED`, `FAILED`, `CLOSED`.
- `RecoveryOptionSet` supports `WAIT`, `REFUND`, `COMPENSATION`, scoped
  `REACCOMMODATION`, and `MANUAL`. RULING (2026-07-09):
  `REACCOMMODATION` is generated only for `disruptionType=MISSED_CONNECTION`
  cases reported by `reportedBy.actorType=SYSTEM` with
  `evidence.sourceSystem=TRANSFER_MANAGEMENT`; all other disruption types and
  case sources still defer `REACCOMMODATION` to a later wave.
- Automatic rules may select `WAIT` only for single-option WAIT sets. A Transfer
  Management missed-connection set containing `WAIT` plus `REACCOMMODATION`
  enters `AWAITING_USER_CHOICE` instead of WAIT auto-through. `WAIT` completes as
  `RECOVERED` with no downstream command when selected. `REFUND` calls the
  existing Post Sales HTTP contract using a deterministic idempotency key and
  converges on `PostSalesApplied`. `COMPENSATION` calls Wallet / Promotion
  `POST /api/v1/benefits` with `issuanceSource=DISRUPTION_COMP`.
  `REACCOMMODATION` calls Transfer Management
  `POST /api/v1/connections/{connectionId}/reaccommodate` with a folded UUID-v7
  idempotency key. `MANUAL` moves the case to manual review.
- Reporting consumption of Disruption Recovery events remains deferred in this
  wave. Notification is active for traveler-facing recovery lifecycle and alert
  facts. Active inbound subscriptions are listed in Consumed upstream events below.

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
| `REACCOMMODATION` | `POST /api/v1/connections/{connectionId}/reaccommodate` | Fold `disruption-recovery:reaccommodation:<caseId>:<optionId>:<connectionId>` into a UUID-v7 wire key. |

## Common payload objects

### Evidence

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceRef` | string | yes | Reference to the customer-service/admin evidence record or consumed upstream event ID. |
| `sourceSystem` | enum | yes | `CUSTOMER_SERVICE`, `ADMIN`, `TRANSFER_MANAGEMENT`, `PROVIDER_INTEGRATION`, or `FULFILLMENT`. |
| `sourceRecordId` | string | yes | Upstream manual row or event identifier. |
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
| `optionType` | enum | yes | `WAIT`, `REFUND`, `COMPENSATION`, `REACCOMMODATION`, or `MANUAL`. |
| `title` | string | yes | Display title. |
| `description` | string | yes | Explanation; must not include sensitive personal data. |
| `executionTarget` | enum | yes | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, `TRANSFER_MANAGEMENT`, or `MANUAL_QUEUE`. |
| `refund` | object | for `REFUND` | `{reasonCode, scope, waiverRef?}` for the Post Sales REFUND case. |
| `compensation` | object | for `COMPENSATION` | `{amount: Money, benefitType, balanceType, validUntil, reasonCode}` for Wallet / Promotion. |
| `manualReason` | string | for `MANUAL` | Reason the case requires manual review. |
| `reaccommodation` | object | for `REACCOMMODATION` | `{connectionId, replacementWindow}`. `replacementWindow` has RFC3339 UTC `plannedArrivalAt`, `nextDepartureAt`, optional `nextCutoffAt`, and `source` (`OPERATIONS` or `SYSTEM`). |
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
| `affectedOrderIds` | array[string] | yes | Affected orders supplied explicitly or resolved from `segmentRef` using the Journey Order projection. |
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
| **Consumers** | notification; deferred: reporting |
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
| **Consumers** | notification; deferred: reporting |
| **Trigger** | `GenerateRecoveryOptions` creates this wave's option set for a case. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionSetId` | string | yes | Option set ID (`ros-<uuid>`). |
| `options` | array[RecoveryOption] | yes | Options; active enum is `WAIT`, `REFUND`, `COMPENSATION`, scoped `REACCOMMODATION`, or `MANUAL`. |
| `requiresUserChoice` | boolean | yes | `false` only for a single-option auto-selectable `WAIT`; `WAIT` plus `REACCOMMODATION` sets this to `true`. |
| `generatedAt` | RFC3339 UTC | yes | Generation timestamp. |
| `expiresAt` | RFC3339 UTC | no | Choice deadline. |
| `status` | enum | yes | `OPTIONS_GENERATED` or `AWAITING_USER_CHOICE` after generation flow. |

### RecoveryOptionSelected

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | notification; deferred: reporting |
| **Trigger** | Automatic `WAIT` selection or user/customer-service `select-option` command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Recovery case ID. |
| `incidentId` | string | yes | Parent incident. |
| `journeyOrderId` | string | yes | Affected order. |
| `optionSetId` | string | yes | Option set containing the selected option. |
| `optionId` | string | yes | Selected option. |
| `optionType` | enum | yes | `WAIT`, `REFUND`, `COMPENSATION`, `REACCOMMODATION`, or `MANUAL`. |
| `selectedBy` | object | yes | Actor that selected the option; `SYSTEM` is allowed only for automatic single-option `WAIT`. |
| `selectedAt` | RFC3339 UTC | yes | Selection timestamp. |
| `status` | enum | yes | `EXECUTING_RECOVERY` for executable options, or `MANUAL_REVIEW` for `MANUAL`. |

### RecoveryExecutionStarted

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | notification; deferred: reporting |
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
| `executionTarget` | enum | yes | `NONE`, `POST_SALES`, `WALLET_PROMOTION`, `TRANSFER_MANAGEMENT`, or `MANUAL_QUEUE`. |
| `idempotencyKey` | string | no | Deterministic UUID-v7 downstream idempotency key for `REFUND`, `COMPENSATION`, or `REACCOMMODATION`. |
| `downstreamRequest` | object | no | Sanitized downstream request summary. For `REFUND`, includes `caseType=REFUND`; for `COMPENSATION`, includes `issuanceSource=DISRUPTION_COMP`; for `REACCOMMODATION`, includes `connectionId`, folded `idempotencyKey`, and `replacementWindow` (RFC3339 UTC times only, no PII). |
| `startedAt` | RFC3339 UTC | yes | Execution start timestamp. |
| `status` | enum | yes | `EXECUTING_RECOVERY` or `MANUAL_REVIEW`. |

### RecoveryCompleted

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | notification, transfer-management; deferred: reporting |
| **Trigger** | `WAIT` completes locally, Wallet / Promotion benefit issuance succeeds, consumed `PostSalesApplied` converges a REFUND execution, or Transfer Management reaccommodation returns `200`. |

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
| `externalRef` | string | no | Post Sales case ID, Wallet benefit ID, Transfer Management replacement `connectionId`, or manual reference when applicable. |
| `status` | enum | yes | `RECOVERED`. |

### RecoveryFailed

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | notification, transfer-management; deferred: reporting |
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
| `previousStatus` | enum | yes | `RECOVERED`, `DECLINED`, or `FAILED` — the only states `CloseRecoveryCase` accepts (domain key-transition table). |
| `closedBy` | object | yes | Closing actor. |
| `closeReason` | string | yes | Closure reason; must not include sensitive personal data. |
| `closedAt` | RFC3339 UTC | yes | Closure timestamp. |
| `status` | enum | yes | `CLOSED`. |

### ServiceAlertPublished

| Field | Description |
|---|---|
| **Producer** | disruption-recovery |
| **Consumers** | notification; deferred: reporting |
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
| `OpenRecoveryCase` | Application flow for each explicit or segment-resolved `affectedOrderId` | `RecoveryCaseOpened` |
| `GenerateRecoveryOptions` | Application flow after impact assessment | `RecoveryOptionsGenerated` |
| `SelectRecoveryOption` | Automatic single-option `WAIT`, or HTTP `POST /api/v1/recovery-cases/{caseId}/select-option` | `RecoveryOptionSelected` |
| `ApplyRecoveryDecision` | Internal execution start after selection | `RecoveryExecutionStarted` |
| `RecordRecoveryExecutionResult` | Local wait completion, Wallet issuance result, consumed `PostSalesApplied`, or Transfer Management reaccommodation response | `RecoveryCompleted` or `RecoveryFailed` |
| `CloseRecoveryCase` | HTTP `POST /api/v1/recovery-cases/{caseId}/close` or manual closure | `RecoveryCaseClosed` |
| `PublishServiceAlert` | Application flow after incident open/merge | `ServiceAlertPublished` |

## Consumed upstream events

| Upstream stream | Event type | Purpose |
|---|---|---|
| `events:post-sales` | `PostSalesApplied` | Converge a selected `REFUND` option when the downstream Post Sales case reaches `APPLIED`. |
| `events:journey-order` | `JourneyOrderCreated` | Maintain the local `segmentRef` -> `journeyOrderId` projection used for disruption fan-out. Payload fields consumed: `orderId`, `segmentRefs`. |
| `events:fulfillment` | `SegmentDelayed`, `SegmentCancelled` | Open or merge a `FULFILLMENT`-sourced incident from the segment signal, resolve affected orders from `segmentRef`, publish a ServiceAlert, and fan out recovery cases through the standard events. |
| `events:provider-integration` | `ProviderSegmentDelayed`, `ProviderSegmentCancelled` (or provider-published `SegmentDelayed` / `SegmentCancelled` with the same segment payload) | Open or merge a `PROVIDER_INTEGRATION`-sourced incident from the provider segment signal, resolve affected orders from `segmentRef`, publish a ServiceAlert, and fan out recovery cases through the standard events. |
| `events:disruption-recovery` | `ServiceAlertPublished` | Build/repair the ServiceAlert read model from the normative alert event payload. |

Fulfillment and Provider Integration segment ingress use the normative
`segmentRef`, `scheduledServiceRef`, `serviceDate`, observed/cancelled/estimated
time fields from their source contracts. Disruption Recovery maps delayed signals
to `disruptionType=DELAY`, cancelled signals to `disruptionType=CANCELLATION`,
sets `reportedBy={actorType:SYSTEM, actorId:<source-service>}`, and records
`evidence.sourceSystem` as `FULFILLMENT` or `PROVIDER_INTEGRATION`. Transfer
Management protected missed-connection reports continue to arrive over HTTP with
`disruptionType=MISSED_CONNECTION`, `reportedBy.actorType=SYSTEM`, and
`evidence.sourceSystem=TRANSFER_MANAGEMENT`.

## Downstream and read-model touchpoints

| Context | Events / model | Purpose |
|---|---|---|
| Notification | active: `ServiceAlertPublished`, `RecoveryCaseOpened`, `RecoveryOptionsGenerated`, `RecoveryOptionSelected`, `RecoveryExecutionStarted`, `RecoveryCompleted`, `RecoveryFailed` | User-facing alert, option, decision, progress, and completion notifications. `RecoveryExecutionStarted` is progress-only; refund executed / compensation issued notifications are emitted only from `RecoveryCompleted`. |
| ServiceAlert read model | active: built from `ServiceAlertPublished`; queryable through the Disruption Recovery API with the event payload fields (`serviceAlertId`, `incidentId`, `disruptionType`, `scheduledServiceRef`, `segmentRef`, `serviceDate`, `audience`, `affectedOrderIds`, `messageSummary`, `publishedAt`) | Customer-service/operations alert feed and affected-order alert lookup. |
| Journey Order segment projection | active: built from `JourneyOrderCreated.segmentRefs`; used only for event-driven `segmentRef` fan-out | Resolve affected orders for HTTP reports and provider/fulfillment segment signals without introducing a cross-service HTTP resolver. |
| Reporting | all Disruption Recovery events | Disruption metrics, waiver/compensation cost attribution, and operational read models. |
| Journey Order | recovery summary events | Order-detail disruption and recovery display. |
