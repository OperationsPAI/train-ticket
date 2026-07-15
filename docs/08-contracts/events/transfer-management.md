# Transfer Management — Events & Commands

Last updated: 2026-07-15

## Scope and activation-wave rulings

This contract lists the Transfer Management events active in ADR-0002 wave 18.
It is bounded by `docs/02-domains/transfer-management.md` and the HTTP contract
in `docs/08-contracts/api/transfer-management.md`.

Activation-wave rulings:

- Active aggregate events are for `TransferPlan`, `Connection`,
  `ConnectionContract`, `MinimumConnectionTimeRule`, and `TransferRiskPolicy`.
  Every risk evaluation carries the active policy version, or `builtin-v1` when
  no active policy exists.
- Fulfillment segment delay/arrival/cancelled events are not active today.
  Runtime facts enter through `POST /api/v1/segment-status-reports`; future
  Fulfillment events may be mapped to the same command material.
- Place Network node/place read integration is active for evaluation metadata
  and topology-weighted minimum connection time. Transfer Management consumes the
  Place Network TransportNode fields `accessTimeMinutes` and
  `walkingEdges[].{toNodeId,walkingTimeMinutes}` exactly as defined in
  `docs/08-contracts/api/place-network.md`: all weights are whole minutes and
  optional. When either endpoint's node has `accessTimeMinutes`, required MCT uses
  the sum of present endpoint access times (missing endpoint weight contributes
  `0`). Otherwise, a direct `walkingEdges` item from `fromNodeRef` to `toNodeRef`
  supplies the required minutes. If those weights are absent or Place Network is
  unavailable, evaluation remains degraded-not-blocking and falls back to the
  builtin default policy.
- Protected missed connections use outbound HTTP to Disruption Recovery. Transfer
  Management stores returned `caseId` mappings and consumes
  `RecoveryCompleted`/`RecoveryFailed` by `caseId` to converge the `Connection`.
  RULING (2026-07-09): scoped `REACCOMMODATION` execution is an inbound HTTP
  command from Disruption Recovery to Transfer Management. Transfer Management
  registers a replacement connection and emits `ConnectionRecovered` with
  replacement fields; when it later consumes the resulting `RecoveryCompleted`
  for the same `caseId`, it idempotently skips because the original connection is
  already `RECOVERED`. `SELF_TRANSFER` misses publish facts only.
- Notification actively consumes traveler-facing connection-state events
  (`TransferAtRisk`, `ConnectionMissed`, and `ConnectionRecovered`). Reporting
  consumes the full Transfer Management stream. Offer Management, Journey Order,
  Trip Planning, and Customer Service consume the event subsets called out in the
  per-event consumer rows. Events not listed for a given consumer are simply
  outside that consumer's concern, not pending work.

All payload fields are camelCase, all enum values are SCREAMING_SNAKE_CASE, and
all timestamps are RFC3339 UTC. Envelope fields, including optional trace context
propagation, follow `docs/08-contracts/messaging.md` and
`docs/08-contracts/shared-primitives.md`. Monetary values, if added in later
extensions, use Money as `{currency, minorUnits}`.

## Event identity and idempotency

Transfer Management producers MUST assign deterministic event IDs per aggregate
transition. The event ID is UUID-v7-shaped with version/variant bits stamped
after folding the seed with SHA-256:

```
transfer-management:<eventType>:<aggregateId>:<aggregateVersion>
```

`aggregateId` is `transferPlanId`, `connectionId`, `connectionContractId`, or
`mctRuleId` depending on the event. If a replayed command causes no new
transition, no new event is emitted. Consumers still deduplicate by envelope
`eventId`.

Wire correlation and causation IDs MUST use `corr-<uuid-v7>` and
`cmd-<uuid-v7>`/`evt-<uuid-v7>` prefixes. HTTP idempotency keys are UUID-v7
shaped. Domain material such as `connectionId:missedAt:journeyOrderId` is folded
into those UUID-v7 command keys; the material string itself is not used as a wire
key. Outbound Disruption Recovery idempotency keys are persisted and reused. Inbound `ReaccommodateConnection` idempotency keys are UUID-v7-shaped and folded from `disruption-recovery:reaccommodation:<caseId>:<optionId>:<connectionId>` by the caller.

## Common payload objects

### ConnectionWindow

| Field | Type | Required | Description |
|---|---|---|---|
| `plannedArrivalAt` | RFC3339 UTC | yes | Previous segment planned arrival. |
| `actualArrivalAt` | RFC3339 UTC | no | Reported actual arrival when known. |
| `nextDepartureAt` | RFC3339 UTC | yes | Next segment planned departure. |
| `nextCutoffAt` | RFC3339 UTC | yes | Boarding/check-in/driver-wait cutoff used for missed detection. |
| `availableMinutes` | integer | yes | Minutes currently available for transfer. |
| `mctMinutes` | integer | yes | Published MCT requirement. |
| `bufferMinutes` | integer | yes | `availableMinutes - mctMinutes`. |

### RiskEvaluation

| Field | Type | Required | Description |
|---|---|---|---|
| `riskEvaluationId` | string | yes | Evaluation ID (`tre-<uuid>`). |
| `riskLevel` | enum | yes | `FEASIBLE`, `TIGHT`, `AT_RISK`, `MISSED`, or `RECOVERED`. |
| `riskPolicyVersion` | string | yes | Active TransferRiskPolicy version, or `builtin-v1` fallback. |
| `mctRuleId` | string | yes | Published MCT rule used. |
| `mctRuleVersion` | integer | yes | MCT rule version used. |
| `availableMinutes` | integer | yes | Available transfer minutes. |
| `requiredMinutes` | integer | yes | Required MCT minutes. |
| `reasons` | array[string] | yes | Explainable reason codes and threshold hits; no PII. |
| `placeGraphVersion` | string | no | Derived from Place Network node IDs and created timestamps when available. |
| `degraded` | boolean | no | `true` when optional Place Network read enhancement failed but evaluation completed. |
| `degradedReasons` | array[string] | no | Degradation codes such as `PLACE_NETWORK_UNAVAILABLE`; required when degraded. |
| `evaluatedAt` | RFC3339 UTC | yes | Evaluation timestamp. |

### ConnectionRef

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionId` | string | yes | Connection ID (`con-<uuid>`). |
| `transferPlanId` | string | yes | Owning transfer plan. |
| `itineraryRef` | string | yes | Trip Planning itinerary reference. |
| `journeyOrderId` | string | no | Purchased order when known. |
| `previousSegmentRef` | string | yes | Previous segment. |
| `nextSegmentRef` | string | yes | Next segment. |
| `travelerRefs` | array[string] | yes | Affected travelers. |
| `replacementOfConnectionId` | string | no | Original connection ID when this connection is a reaccommodation replacement. |

### RecoveryCaseMapping

| Field | Type | Required | Description |
|---|---|---|---|
| `disruptionId` | string | no | Disruption Recovery report ID. |
| `incidentId` | string | no | Disruption Recovery incident ID. |
| `caseIds` | array[string] | yes | Recovery case IDs returned by the response. |
| `outboundIdempotencyKey` | string | yes | Persisted UUID-v7 idempotency key used for the HTTP command. |
| `recoveryTriggerStatus` | enum | yes | `PENDING`, `OPENED`, or `FAILED`. |
| `failureReason` | string | no | Sanitized failure reason. |

## Published Events

### TransferPlanCreated

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | `CreateTransferPlan` accepted from `POST /api/v1/transfer-plans`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `transferPlanId` | string | yes | Transfer plan ID (`tpl-<uuid>`). |
| `itineraryRef` | string | yes | Trip Planning itinerary reference. |
| `planningSnapshotVersion` | integer | yes | Itinerary snapshot version/hash. |
| `journeyOrderId` | string | no | Purchased order when known. |
| `travelerRefs` | array[string] | yes | Traveler refs. |
| `status` | enum | yes | `DRAFT` or `EVALUATING` if evaluation starts synchronously. |
| `riskPolicyVersion` | string | yes | Active TransferRiskPolicy version or `builtin-v1`. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `expiresAt` | RFC3339 UTC | no | Planning/offer expiry. |

### TransferPlanEvaluated

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | offer-management, reporting |
| **Trigger** | Plan evaluation completed or refreshed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `transferPlanId` | string | yes | Transfer plan ID. |
| `itineraryRef` | string | yes | Trip Planning itinerary reference. |
| `planningSnapshotVersion` | integer | yes | Snapshot version evaluated. |
| `journeyOrderId` | string | no | Purchased order when known. |
| `status` | enum | yes | `EVALUATED` or `UNSERVICEABLE`. |
| `evaluationVersion` | integer | yes | Monotonic evaluation version. |
| `connectionIds` | array[string] | yes | Connections included in the plan. |
| `riskPolicyVersion` | string | yes | Active TransferRiskPolicy version or `builtin-v1`. |
| `evaluatedAt` | RFC3339 UTC | yes | Evaluation timestamp. |
| `unserviceableReasons` | array[string] | no | Required when status is `UNSERVICEABLE`. |

### TransferPlanExpired

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | Planning/offer window expires. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `transferPlanId` | string | yes | Transfer plan ID. |
| `itineraryRef` | string | yes | Trip Planning itinerary reference. |
| `previousStatus` | enum | yes | Status before expiry. |
| `status` | enum | yes | `EXPIRED`. |
| `expiredAt` | RFC3339 UTC | yes | Expiry timestamp. |
| `reason` | string | no | Non-PII expiry reason. |

### ConnectionRegistered

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | `RegisterConnection` command creates a connection. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionId` | string | yes | Connection ID. |
| `transferPlanId` | string | yes | Owning plan. |
| `itineraryRef` | string | yes | Source itinerary. |
| `journeyOrderId` | string | no | Purchased order when known. |
| `previousSegmentRef` | string | yes | Previous segment. |
| `nextSegmentRef` | string | yes | Next segment. |
| `travelerRefs` | array[string] | yes | Affected travelers. |
| `replacementOfConnectionId` | string | no | Original connection ID when this connection is a reaccommodation replacement. |
| `fromNodeRef` | string | yes | Arrival node/place. |
| `toNodeRef` | string | yes | Departure node/place. |
| `fromNodeType` | enum | yes | Arrival node type. |
| `toNodeType` | enum | yes | Departure node type. |
| `transferCategory` | enum | yes | MCT transfer category. |
| `contractId` | string | yes | Associated contract. |
| `contractType` | enum | yes | Contract type snapshot. |
| `status` | enum | yes | `PLANNED`, `FEASIBLE`, `TIGHT`, or `AT_RISK` after initial evaluation. |
| `window` | object | yes | ConnectionWindow. |
| `registeredAt` | RFC3339 UTC | yes | Registration timestamp. |

### TransferRiskEvaluated

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | Initial, scheduled, or segment-report-driven risk evaluation completes. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connection` | object | yes | ConnectionRef. |
| `previousStatus` | enum | no | Prior connection status if it changed. |
| `status` | enum | yes | Current connection status. |
| `evaluation` | object | yes | RiskEvaluation. |
| `window` | object | yes | ConnectionWindow used. |
| `sourceReportId` | string | no | Segment status report ID that triggered evaluation. |

### TransferAtRisk

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | notification, reporting |
| **Trigger** | A connection transitions to `AT_RISK`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connection` | object | yes | ConnectionRef. |
| `previousStatus` | enum | yes | Prior status, normally `FEASIBLE` or `TIGHT`. |
| `status` | enum | yes | `AT_RISK`. |
| `riskLevel` | enum | yes | `AT_RISK`. |
| `riskPolicyVersion` | string | yes | Active TransferRiskPolicy version or `builtin-v1`. |
| `reasons` | array[string] | yes | Explainable risk reasons. |
| `window` | object | yes | ConnectionWindow. |
| `detectedAt` | RFC3339 UTC | yes | Detection timestamp. |

### ConnectionMissed

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | notification, journey-order, customer-service, reporting |
| **Trigger** | A connection transitions to `MISSED`. Protected contracts also start the outbound Disruption Recovery HTTP command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connection` | object | yes | ConnectionRef. |
| `previousStatus` | enum | yes | Prior status. |
| `status` | enum | yes | `MISSED`. |
| `riskLevel` | enum | yes | `MISSED`. |
| `contractType` | enum | yes | Contract type snapshot. |
| `missedAt` | RFC3339 UTC | yes | Missed decision timestamp. |
| `missedCause` | enum | yes | `PREVIOUS_SEGMENT_DELAYED`, `PREVIOUS_SEGMENT_CANCELLED`, `NEXT_SEGMENT_CANCELLED`, `CUTOFF_EXPIRED`, or `OPS_DECLARED`. |
| `window` | object | yes | ConnectionWindow. |
| `recoveryRequired` | boolean | yes | `true` only for `PROTECTED` and `SUPPLIER_PROTECTED`. |
| `recovery` | object | no | RecoveryCaseMapping when an outbound call has been attempted or opened. |

### ConnectionRecovered

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | notification, journey-order, customer-service, reporting |
| **Trigger** | Consumed Disruption Recovery `RecoveryCompleted` matches a stored `caseId`, an explicit controlled recovery command restores the connection, or Disruption Recovery calls `POST /api/v1/connections/{connectionId}/reaccommodate`. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connection` | object | yes | ConnectionRef. |
| `previousStatus` | enum | yes | `MISSED` or `AT_RISK`. |
| `status` | enum | yes | `RECOVERED`. |
| `riskLevel` | enum | yes | `RECOVERED`. |
| `recoveryCaseId` | string | no | Matched Disruption Recovery case ID. |
| `replacementConnectionId` | string | no | Replacement connection registered by the `REACCOMMODATION` endpoint. Required for reaccommodation-triggered recovery. |
| `replacementWindow` | object | no | Declared replacement window with RFC3339 UTC `plannedArrivalAt`, `nextDepartureAt`, optional `nextCutoffAt`, and `source` (`OPERATIONS` or `SYSTEM`). Required when `replacementConnectionId` is present. |
| `reaccommodatedAt` | RFC3339 UTC | no | Time the replacement connection was registered. Present for reaccommodation-triggered recovery and equal to `recoveredAt`. |
| `disruptionRecoveryEventId` | string | no | Consumed envelope event ID when event-driven. |
| `recoveredAt` | RFC3339 UTC | yes | Recovery convergence timestamp. |
| `recoverySummary` | string | no | Sanitized summary; no unmasked PII. |

RULING (2026-07-09): REQ-121 uses `ConnectionRecovered` rather than a separate
`ConnectionReaccommodated` event. The additive fields `replacementConnectionId`,
`replacementWindow`, and `reaccommodatedAt` carry the reaccommodation-specific
delta while preserving the existing connection-convergence subscription.
Consumers that do not need replacement details may continue to treat the event as
a normal transition to `RECOVERED`.

### ConnectionRecoveryFailed

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | customer-service, reporting |
| **Trigger** | Consumed Disruption Recovery `RecoveryFailed` matches a stored `caseId`, or the outbound recovery report failed permanently. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connection` | object | yes | ConnectionRef. |
| `status` | enum | yes | Current status remains `MISSED`. |
| `recoveryCaseId` | string | no | Matched recovery case ID. |
| `disruptionRecoveryEventId` | string | no | Consumed envelope event ID when event-driven. |
| `failedAt` | RFC3339 UTC | yes | Failure timestamp. |
| `reason` | string | yes | Sanitized failure reason; no sensitive personal data. |

### ConnectionContractProposed

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | offer-management, reporting |
| **Trigger** | Contract option proposed for a connection. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionContractId` | string | yes | Contract ID (`cct-<uuid>`). |
| `connectionId` | string | yes | Connection ID. |
| `contractType` | enum | yes | Contract type. |
| `status` | enum | yes | `PROPOSED`, `ELIGIBLE`, or `REJECTED`. |
| `responsibleParty` | enum | yes | `PLATFORM`, `SUPPLIER`, `PLATFORM_ASSISTANCE`, or `TRAVELER`. |
| `coverageSummary` | string | yes | Safe display summary. |
| `disclosureVersion` | string | yes | Disclosure version. |
| `termsSnapshotRef` | string | no | Immutable terms snapshot. |
| `proposedAt` | RFC3339 UTC | yes | Proposal timestamp. |

### ConnectionContractConfirmed

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | journey-order, reporting |
| **Trigger** | Offer/order accepts the contract snapshot. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionContractId` | string | yes | Contract ID. |
| `connectionId` | string | yes | Connection ID. |
| `contractType` | enum | yes | Contract type. |
| `previousStatus` | enum | yes | Previous contract status. |
| `status` | enum | yes | `CONFIRMED`. |
| `acceptedByRef` | string | yes | Offer/order/user/system acceptance reference. |
| `acceptedVersion` | string | yes | Accepted disclosure/terms version. |
| `confirmedAt` | RFC3339 UTC | yes | Confirmation timestamp. |

### ConnectionContractWithdrawn

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | Contract is withdrawn, voided, or rejected by controlled command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionContractId` | string | yes | Contract ID. |
| `connectionId` | string | yes | Connection ID. |
| `contractType` | enum | yes | Contract type. |
| `previousStatus` | enum | yes | Previous contract status. |
| `status` | enum | yes | `VOIDED` or `REJECTED`. |
| `withdrawnBy` | object | yes | ActorRef. |
| `reason` | string | yes | Non-PII reason. |
| `withdrawnAt` | RFC3339 UTC | yes | Withdrawal timestamp. |


### RiskPolicyActivated

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | Operations activates a `TransferRiskPolicy`; any previous active policy is retired. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `riskPolicyId` | string | yes | Policy ID (`trp-<uuid>`). |
| `version` | string | yes | Version label that evaluations write to `riskPolicyVersion`. |
| `status` | enum | yes | `ACTIVE`. |
| `thresholds` | object | yes | `{tightMinutes, atRiskMinutes}` integer minute boundaries. |
| `createdBy` | object | yes | ActorRef from creation. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `activatedBy` | object | yes | ActorRef that activated the policy. |
| `activatedAt` | RFC3339 UTC | yes | Activation timestamp. |

### MctRuleCreated

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | reporting |
| **Trigger** | Operations creates a draft MCT rule. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `mctRuleId` | string | yes | MCT rule ID (`mct-<uuid>`). |
| `version` | integer | yes | Rule version. |
| `status` | enum | yes | `DRAFT`. |
| `fromNodeType` | enum | yes | Arrival node type. |
| `toNodeType` | enum | yes | Departure node type. |
| `transferCategory` | enum | yes | Transfer category. |
| `minimumMinutes` | integer | yes | Non-negative MCT minutes. |
| `conditions` | object | yes | Non-PII condition flags. |
| `validFrom` | RFC3339 UTC | yes | Effective start. |
| `validUntil` | RFC3339 UTC | no | Effective end. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |

### MctRulePublished

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | trip-planning, offer-management, reporting |
| **Trigger** | Operations publishes a validated MCT rule version. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `mctRuleId` | string | yes | MCT rule ID. |
| `version` | integer | yes | Published immutable version. |
| `previousStatus` | enum | yes | Previous status. |
| `status` | enum | yes | `PUBLISHED`. |
| `fromNodeType` | enum | yes | Arrival node type. |
| `toNodeType` | enum | yes | Departure node type. |
| `transferCategory` | enum | yes | Transfer category. |
| `minimumMinutes` | integer | yes | Non-negative MCT minutes. |
| `conditions` | object | yes | Non-PII condition flags. |
| `validFrom` | RFC3339 UTC | yes | Effective start. |
| `validUntil` | RFC3339 UTC | no | Effective end. |
| `publishedBy` | object | yes | ActorRef. |
| `publishedAt` | RFC3339 UTC | yes | Publish timestamp. |

### MctRuleRetired

| Field | Description |
|---|---|
| **Producer** | transfer-management |
| **Consumers** | trip-planning, reporting |
| **Trigger** | Operations retires a published MCT rule version. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `mctRuleId` | string | yes | MCT rule ID. |
| `version` | integer | yes | Retired version. |
| `previousStatus` | enum | yes | Previous status, normally `PUBLISHED`. |
| `status` | enum | yes | `RETIRED`. |
| `retiredBy` | object | yes | ActorRef. |
| `retireReason` | string | yes | Non-PII reason. |
| `retiredAt` | RFC3339 UTC | yes | Retire timestamp. |

## Accepted Commands

| Command | Sender / Trigger | Produced event |
|---|---|---|
| `CreateTransferPlan` | HTTP `POST /api/v1/transfer-plans` | `TransferPlanCreated` |
| `EvaluateTransferPlan` | HTTP `POST /api/v1/transfer-plans/{transferPlanId}/evaluate` | `TransferPlanEvaluated`, `TransferRiskEvaluated` |
| `RegisterConnection` | HTTP `POST /api/v1/connections` or application flow after plan creation | `ConnectionRegistered`, `TransferRiskEvaluated` |
| `ReportSegmentStatus` | HTTP `POST /api/v1/segment-status-reports` | `TransferRiskEvaluated`, `TransferAtRisk`, `ConnectionMissed`, `ConnectionRecovered`, `ConnectionRecoveryFailed` |
| `ReaccommodateConnection` | HTTP `POST /api/v1/connections/{connectionId}/reaccommodate` from Disruption Recovery | `ConnectionRegistered`, `ConnectionRecovered` |
| `ProposeConnectionContract` | HTTP `POST /api/v1/connection-contracts` | `ConnectionContractProposed` |
| `ConfirmConnectionContract` | HTTP `POST /api/v1/connection-contracts/{connectionContractId}/confirm` | `ConnectionContractConfirmed` |
| `WithdrawConnectionContract` | HTTP `POST /api/v1/connection-contracts/{connectionContractId}/withdraw` | `ConnectionContractWithdrawn` |
| `CreateMctRule` | HTTP `POST /api/v1/mct-rules` | `MctRuleCreated` |
| `PublishMctRule` | HTTP `POST /api/v1/mct-rules/{mctRuleId}/publish` | `MctRulePublished` |
| `RetireMctRule` | HTTP `POST /api/v1/mct-rules/{mctRuleId}/retire` | `MctRuleRetired` |
| `CreateRiskPolicy` | HTTP `POST /api/v1/risk-policies` | none until activation |
| `ActivateRiskPolicy` | HTTP `POST /api/v1/risk-policies/{riskPolicyId}/activate` | `RiskPolicyActivated` |
| `RecordRecoveryCompleted` | Consumed Disruption Recovery `RecoveryCompleted` by `caseId`; if the matching connection is already `RECOVERED` by `ReaccommodateConnection`, idempotently skip with no event | `ConnectionRecovered` or no-op |
| `RecordRecoveryFailed` | Consumed Disruption Recovery `RecoveryFailed` by `caseId` | `ConnectionRecoveryFailed` |

## Consumed upstream events

| Upstream stream | Event type | Purpose |
|---|---|---|
| `events:disruption-recovery` | `RecoveryCompleted` | Match payload `caseId` to stored recovery-case mapping and transition the related connection to `RECOVERED`; for self-executed `REACCOMMODATION`, an already `RECOVERED` connection with the same `caseId` is an idempotent no-op. |
| `events:disruption-recovery` | `RecoveryFailed` | Match payload `caseId` to stored recovery-case mapping, keep the connection `MISSED`, and record sanitized failure evidence. |

Fulfillment runtime facts are consumed when produced and mapped to the same
segment-status command material; the system/ops segment-status reporting endpoint
remains the active fallback adapter.
