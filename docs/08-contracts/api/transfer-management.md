# Transfer Management — HTTP API

Last updated: 2026-07-09

## Overview

Transfer Management owns transfer feasibility, connection runtime state,
connection responsibility contracts, and published minimum connection time (MCT)
rules. This contract activates the bounded context from
`docs/02-domains/transfer-management.md` with ADR-0002 wave-18 scope cuts.

Activation-wave rulings:

- Active aggregates are `TransferPlan`, `Connection`, `ConnectionContract`, and
  `TransferRiskPolicy`. `MinimumConnectionTimeRule` is active as operations CRUD
  plus publish/retire; a `PUBLISHED` version is immutable. Risk evaluations use
  the currently active risk policy version. If no active policy exists, the
  backwards-compatible built-in policy `builtin-v1` is used and recorded.
- `Connection` uses the nine-state runtime machine below. RULING: once a
  connection reaches `MISSED`, it MUST NOT transition back to `FEASIBLE`; it may
  only converge to `RECOVERED`, `SELF_HANDLED`, or a terminal state.
- Fulfillment publishes segment-level `SegmentDelayed`, `SegmentArrived`, and
  `SegmentCancelled` facts. Runtime inputs are accepted from those events and
  through the system/ops fallback endpoint `POST /api/v1/segment-status-reports`;
  both paths share aggregate invariants.
- Evaluation reads the itinerary snapshot from Trip Planning by `itineraryRef`,
  reads Place Network transport nodes by `fromNodeRef`/`toNodeRef` with
  `GET /api/v1/transport-nodes/{nodeId}` and parent place type via
  `GET /api/v1/places/{placeId}`, uses this domain's published MCT rules, and
  derives schedule windows from the itinerary snapshot plus accepted
  segment-status reports. The current Place Network contract exposes node
  identity, place ID, serving modes, and created timestamp only; it does not
  expose walking/access-time weights, so MCT minutes remain sourced from
  published Transfer Management MCT rules. Read failures during evaluation are
  non-blocking and produce `degraded=true` with `degradedReasons`. Missing nodes
  during registration are rejected with validation failure.
- Closed loop with Disruption Recovery is HTTP-first. When a protected connection
  (`PROTECTED` or `SUPPLIER_PROTECTED`) becomes `MISSED`, Transfer Management
  calls Disruption Recovery `POST /api/v1/disruptions` as a `SYSTEM` report with
  `disruptionType=MISSED_CONNECTION` and
  `evidence.sourceSystem=TRANSFER_MANAGEMENT`. It persists the outbound UUID-v7
  idempotency key and the `recoveryCases[].caseId` values from the response.
  `SELF_TRANSFER` missed connections publish facts only and do not open recovery
  cases. RULING (2026-07-09): Disruption Recovery may now offer scoped
  `REACCOMMODATION` for those Transfer Management missed-connection cases. The
  execution path calls the new Transfer Management reaccommodation endpoint,
  registers a replacement connection, marks the original connection `RECOVERED`,
  and emits `ConnectionRecovered` with `replacementConnectionId`.

Field shapes reference `docs/08-contracts/shared-primitives.md` for IDs,
timestamps, event envelope fields, pagination conventions, and Money
(`{currency, minorUnits}` when used by future extensions). All timestamps are
RFC3339 UTC. JSON fields are camelCase and enum values are SCREAMING_SNAKE_CASE.
Error codes follow `docs/08-contracts/api/README.md` / REQ-079.

## Common enums

| Enum | Values |
|---|---|
| `transferPlanStatus` | `DRAFT`, `EVALUATING`, `EVALUATED`, `UNSERVICEABLE`, `PUBLISHED`, `REFRESHING`, `EXPIRED` |
| `connectionStatus` | `PLANNED`, `FEASIBLE`, `TIGHT`, `AT_RISK`, `MISSED`, `RECOVERED`, `SELF_HANDLED`, `COMPLETED`, `INVALIDATED` |
| `connectionContractType` | `PROTECTED`, `SUPPLIER_PROTECTED`, `PLATFORM_ASSISTED`, `SELF_TRANSFER` |
| `connectionContractStatus` | `PROPOSED`, `ELIGIBLE`, `REJECTED`, `CONFIRMED`, `ACTIVE`, `TRIGGERED`, `SETTLED`, `VOIDED` |
| `riskLevel` | `FEASIBLE`, `TIGHT`, `AT_RISK`, `MISSED`, `RECOVERED` |
| `transferCategory` | `SAME_STATION`, `CROSS_STATION`, `IN_STATION`, `TERMINAL_CHANGE`, `AIRPORT`, `PORT`, `BUS_TERMINAL`, `RIDESHARE_CONNECTOR`, `OTHER` |
| `nodeType` | `STATION`, `AIRPORT_TERMINAL`, `PORT_TERMINAL`, `BUS_STOP`, `RIDESHARE_PICKUP`, `WALKING_NODE`, `OTHER` |
| `segmentReportType` | `DELAY`, `ARRIVAL`, `CANCELLED` |
| `mctRuleStatus` | `DRAFT`, `VALIDATED`, `PUBLISHED`, `RETIRED` |
| `recoveryTriggerStatus` | `NOT_REQUIRED`, `PENDING`, `OPENED`, `FAILED` |

`riskLevel` is an evaluation output and is intentionally separate from
`connectionStatus`, which is the aggregate state machine.

## State machines

### TransferPlan

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `DRAFT` | Plan shell was created from an itinerary reference. | `EVALUATING`, `EXPIRED` |
| `EVALUATING` | Connections are being assessed against itinerary, schedule window, and MCT rules. | `EVALUATED`, `UNSERVICEABLE` |
| `EVALUATED` | All active connections have an assessment result. | `PUBLISHED`, `REFRESHING`, `EXPIRED` |
| `UNSERVICEABLE` | Required data is missing or at least one transfer is impossible for this wave. | `REFRESHING`, `EXPIRED` |
| `PUBLISHED` | Plan is visible to Trip Planning, Offer Management, or Journey Order. | `REFRESHING`, `EXPIRED` |
| `REFRESHING` | Upstream schedule/status data changed and the plan is being re-evaluated. | `EVALUATED`, `UNSERVICEABLE`, `EXPIRED` |
| `EXPIRED` | Planning/offer window ended. | Terminal |

### Connection

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `PLANNED` | Connection was registered from planned itinerary/schedule data. | `FEASIBLE`, `TIGHT`, `AT_RISK`, `INVALIDATED` |
| `FEASIBLE` | Available window exceeds MCT and buffer threshold. | `TIGHT`, `AT_RISK`, `COMPLETED`, `INVALIDATED` |
| `TIGHT` | Still reachable but with insufficient buffer. | `FEASIBLE`, `AT_RISK`, `MISSED`, `COMPLETED` |
| `AT_RISK` | Delay, cancellation, baggage/security, or schedule report creates high risk. | `TIGHT`, `MISSED`, `RECOVERED` |
| `MISSED` | Next-segment cutoff cannot be met or the next segment is closed/cancelled. | `RECOVERED`, `SELF_HANDLED` |
| `RECOVERED` | Disruption Recovery completed an acceptable recovery path or the connection was otherwise restored. | `COMPLETED` |
| `SELF_HANDLED` | Self-transfer traveler handles or abandons the missed connection without protected recovery. | `COMPLETED` |
| `COMPLETED` | Next segment boarded/started or the risk window ended. | Terminal |
| `INVALIDATED` | Order cancellation, itinerary replacement, or contract withdrawal invalidated the connection. | Terminal |

RULING: `MISSED` cannot transition to `FEASIBLE` even if a later status report
arrives; later facts are recorded as timeline evidence and may produce
`RECOVERED` only through explicit recovery convergence.

### ConnectionContract

| Status | Meaning | Allowed next statuses |
|---|---|---|
| `PROPOSED` | Contract option was generated for a connection. | `ELIGIBLE`, `REJECTED`, `VOIDED` |
| `ELIGIBLE` | Validation passed and the contract may be accepted. | `CONFIRMED`, `VOIDED` |
| `REJECTED` | The connection is not eligible for this contract option. | Terminal |
| `CONFIRMED` | Offer/order accepted the contract snapshot. | `ACTIVE`, `VOIDED` |
| `ACTIVE` | Journey has started and runtime monitoring may trigger responsibility. | `TRIGGERED`, `VOIDED` |
| `TRIGGERED` | At-risk/missed state activated responsibility handling. | `SETTLED`, `VOIDED` |
| `SETTLED` | Responsibility was handed off to recovery, assistance, or self-transfer facts. | Terminal |
| `VOIDED` | Order/itinerary change removed this contract. | Terminal |

## Identity and idempotency

HTTP commands that mutate state require an `Idempotency-Key` header. The header
value MUST be UUID-v7-shaped. A replay with the same body returns the original
response; reuse with a different body returns `IDEMPOTENCY_KEY_REUSED`.

Domain idempotency material from the domain document is not placed directly on
the wire. The application folds stable material with SHA-256, stamps UUID version
7 and RFC-4122 variant bits, and persists the resulting UUID-v7-shaped command
key.

| Command | Material folded into UUID-v7 idempotency key |
|---|---|
| Create/evaluate transfer plan | `itineraryRef`, `planningSnapshotVersion`, `trigger` (`API`/`REFRESH`) |
| Register connection | `journeyOrderId`, `previousSegmentRef`, `nextSegmentRef`, `travelerSetHash` |
| Segment status report | `sourceSystem`, `sourceRecordId`, `segmentRef`, `reportType`, `observedAt` |
| Publish MCT rule | `mctRuleId`, `version` |
| Open Disruption Recovery report | `connectionId`, `missedAt`, `connectionVersion`, `journeyOrderId` |
| Reaccommodate connection | `connectionId`, `caseId`, `replacementWindow.plannedArrivalAt`, `replacementWindow.nextDepartureAt` |

API-triggered commands use the client-supplied `Idempotency-Key` header
directly as the command key; the fold materials above apply to commands the
service originates internally (scheduled refreshes, event-driven
re-evaluations, outbound reports).

Outbound HTTP idempotency keys used for Disruption Recovery MUST be persisted
before the call is attempted and reused on retry. Correlation and causation IDs
on outbound events/commands use `corr-<uuid-v7>` and `cmd-<uuid-v7>` prefixes.

## Resource representations

### TransferPlan

| Field | Type | Required | Description |
|---|---|---|---|
| `transferPlanId` | string | yes | Canonical transfer plan ID (`tpl-<uuid>`). |
| `itineraryRef` | string | yes | Trip Planning itinerary reference; the itinerary snapshot self-authenticates this reference. |
| `planningSnapshotVersion` | integer | yes | Version/hash of the itinerary snapshot used for evaluation. |
| `journeyOrderId` | string | no | Order ID when evaluating a purchased itinerary. |
| `status` | enum | yes | Transfer plan status. |
| `connections` | array[ConnectionSummary] | yes | Connections produced from adjacent itinerary legs. |
| `evaluationVersion` | integer | yes | Monotonic plan evaluation version. |
| `riskPolicyVersion` | string | yes | Active TransferRiskPolicy version used by latest plan evaluation, or `builtin-v1` fallback. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |
| `expiresAt` | RFC3339 UTC | no | Planning/offer expiry. |

### ConnectionSummary

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionId` | string | yes | Connection ID (`con-<uuid>`). |
| `previousSegmentRef` | string | yes | Previous itinerary/order segment reference. |
| `nextSegmentRef` | string | yes | Next itinerary/order segment reference. |
| `transferCategory` | enum | yes | Transfer category used for MCT lookup. |
| `status` | enum | yes | Connection runtime status. |
| `riskLevel` | enum | yes | Latest evaluation risk level. |

### Connection

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionId` | string | yes | Connection ID (`con-<uuid>`). |
| `transferPlanId` | string | yes | Owning transfer plan. |
| `journeyOrderId` | string | no | Purchased order when known. Required before protected recovery is opened. |
| `itineraryRef` | string | yes | Source Trip Planning itinerary reference. |
| `previousSegmentRef` | string | yes | Previous segment reference. |
| `nextSegmentRef` | string | yes | Next segment reference. |
| `travelerRefs` | array[string] | yes | Traveler references affected by this connection. |
| `fromNodeRef` | string | yes | Arrival node/place reference from itinerary snapshot. |
| `toNodeRef` | string | yes | Departure node/place reference from itinerary snapshot. |
| `fromNodeType` | enum | yes | Node type used for MCT lookup. |
| `toNodeType` | enum | yes | Node type used for MCT lookup. |
| `transferCategory` | enum | yes | Transfer category used for MCT lookup. |
| `contractId` | string | yes | Confirmed or proposed connection contract ID. |
| `contractType` | enum | yes | Contract type snapshot. |
| `status` | enum | yes | Connection runtime status. |
| `latestEvaluation` | RiskEvaluation | yes | Latest risk evaluation. |
| `window` | ConnectionWindow | yes | Planned/actual transfer window. |
| `replacementOfConnectionId` | string | no | Original connection ID when this connection was registered as a reaccommodation replacement. |
| `replacementConnectionId` | string | no | Replacement connection ID when this original connection has been reaccommodated. |
| `recovery` | RecoveryCaseMapping | no | Present after protected missed-connection report opens recovery cases. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |

### ConnectionWindow

| Field | Type | Required | Description |
|---|---|---|---|
| `plannedArrivalAt` | RFC3339 UTC | yes | Previous segment planned arrival. |
| `actualArrivalAt` | RFC3339 UTC | no | Reported actual arrival when known. |
| `nextDepartureAt` | RFC3339 UTC | yes | Next segment planned departure. |
| `nextCutoffAt` | RFC3339 UTC | yes | Boarding/check-in/driver-wait cutoff used for missed detection. |
| `availableMinutes` | integer | yes | Minutes currently available for the connection; may be negative after cutoff. |
| `mctMinutes` | integer | yes | Required minimum connection time from a published MCT rule. |
| `bufferMinutes` | integer | yes | `availableMinutes - mctMinutes`. |

### RiskEvaluation

| Field | Type | Required | Description |
|---|---|---|---|
| `riskEvaluationId` | string | yes | Evaluation ID (`tre-<uuid>`). |
| `riskLevel` | enum | yes | Risk output, not aggregate state. |
| `riskPolicyVersion` | string | yes | Active TransferRiskPolicy version used, or `builtin-v1` fallback when no active policy exists. |
| `mctRuleId` | string | yes | Published MCT rule used. |
| `mctRuleVersion` | integer | yes | Published MCT rule version. |
| `availableMinutes` | integer | yes | Available transfer minutes used in the calculation. |
| `requiredMinutes` | integer | yes | MCT minutes required. |
| `reasons` | array[string] | yes | Explainable reason codes such as `THRESHOLD_TIGHT_BUFFER_LT_10_MIN`, `PREVIOUS_SEGMENT_DELAYED`, or `NEXT_SEGMENT_CANCELLED`. |
| `placeGraphVersion` | string | no | Snapshot identifier derived from Place Network node IDs and node `createdAt` timestamps when topology read succeeds. |
| `degraded` | boolean | no | `true` when optional Place Network read enhancement failed but evaluation completed. |
| `degradedReasons` | array[string] | no | Non-PII degradation codes, e.g. `PLACE_NETWORK_UNAVAILABLE`. Required when `degraded=true`. |
| `evaluatedAt` | RFC3339 UTC | yes | Evaluation timestamp. |

### ConnectionContract

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionContractId` | string | yes | Contract ID (`cct-<uuid>`). |
| `connectionId` | string | yes | Connection this contract describes. |
| `contractType` | enum | yes | Contract type. |
| `status` | enum | yes | Contract status. |
| `responsibleParty` | enum | yes | `PLATFORM`, `SUPPLIER`, `PLATFORM_ASSISTANCE`, or `TRAVELER`. |
| `coverageSummary` | string | yes | User/customer-service safe summary; no unmasked PII. |
| `disclosureVersion` | string | yes | Disclosure copy/version accepted by offer/order. |
| `termsSnapshotRef` | string | no | Immutable supplier/platform terms snapshot. |
| `confirmedAt` | RFC3339 UTC | no | Confirmation timestamp. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `updatedAt` | RFC3339 UTC | yes | Last mutation timestamp. |

### MinimumConnectionTimeRule

| Field | Type | Required | Description |
|---|---|---|---|
| `mctRuleId` | string | yes | MCT rule ID (`mct-<uuid>`). |
| `version` | integer | yes | Monotonic rule version; `PUBLISHED` versions are immutable. |
| `status` | enum | yes | MCT rule status. |
| `fromNodeType` | enum | yes | Arrival node type matched by the rule. |
| `toNodeType` | enum | yes | Departure node type matched by the rule. |
| `transferCategory` | enum | yes | Transfer category matched by the rule. |
| `minimumMinutes` | integer | yes | Required minutes; must be positive. |
| `conditions` | object | yes | Structured non-PII conditions such as baggage/security/accessibility flags. |
| `validFrom` | RFC3339 UTC | yes | Rule effective start. |
| `validUntil` | RFC3339 UTC | no | Rule effective end. |
| `publishedAt` | RFC3339 UTC | no | Publish timestamp. |
| `retiredAt` | RFC3339 UTC | no | Retire timestamp. |


### TransferRiskPolicy

| Field | Type | Required | Description |
|---|---|---|---|
| `riskPolicyId` | string | yes | Risk policy ID (`trp-<uuid>`). |
| `version` | string | yes | Operator supplied immutable version label written into evaluations. |
| `status` | enum | yes | `DRAFT`, `ACTIVE`, or `RETIRED`. |
| `thresholds.tightMinutes` | integer | yes | Buffer below this minute boundary is `TIGHT` unless the at-risk boundary is hit. |
| `thresholds.atRiskMinutes` | integer | yes | Buffer below this minute boundary is `AT_RISK`; at-risk applies when buffer is below this boundary; setting a wide value makes at-risk more aggressive. |
| `createdBy` | ActorRef | yes | Operations actor creating the policy. |
| `createdAt` | RFC3339 UTC | yes | Creation timestamp. |
| `activatedAt` | RFC3339 UTC | no | Activation timestamp. |
| `retiredAt` | RFC3339 UTC | no | Automatic retirement timestamp after another policy is activated. |

Active policies are immutable through the public API. Activating a new policy
automatically retires the previously active policy. Retired policies cannot be
reactivated.

### ReaccommodateConnectionRequest

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | string | yes | Disruption Recovery case ID (`rcv-<uuid>`) selecting the `REACCOMMODATION` option. Must match a stored recovery-case mapping for the target connection. |
| `replacementWindow` | object | yes | Declared replacement arrival/departure window. See `ReplacementWindow`. |

### ReplacementWindow

| Field | Type | Required | Description |
|---|---|---|---|
| `plannedArrivalAt` | RFC3339 UTC | yes | New planned/declared arrival time for the replacement connection's previous leg. |
| `nextDepartureAt` | RFC3339 UTC | yes | New planned/declared departure time for the replacement connection's next leg. |
| `nextCutoffAt` | RFC3339 UTC | no | Replacement boarding/check-in/driver-wait cutoff when known. |
| `source` | enum | yes | `OPERATIONS` or `SYSTEM`; identifies the declaration source supplied through Disruption Recovery option parameters. |

### SegmentStatusReport

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentStatusReportId` | string | yes | Report ID (`tsr-<uuid>`). |
| `segmentRef` | string | yes | Segment reported by system/ops. |
| `reportType` | enum | yes | `DELAY`, `ARRIVAL`, or `CANCELLED`. |
| `reportedBy` | object | yes | ActorRef; `actorType` is normally `SYSTEM` or `OPERATIONS`. |
| `sourceSystem` | enum | yes | `OPERATIONS`, `ADMIN`, or future `FULFILLMENT`; current production uses ops/system reporting. |
| `sourceRecordId` | string | yes | Source report row/event identifier for idempotency material. |
| `observedAt` | RFC3339 UTC | yes | When the operational fact was observed. |
| `estimatedArrivalAt` | RFC3339 UTC | for `DELAY` | Updated ETA for delayed previous segment. |
| `actualArrivalAt` | RFC3339 UTC | for `ARRIVAL` | Actual arrival time. |
| `cancelledAt` | RFC3339 UTC | for `CANCELLED` | Cancellation time. |
| `reason` | string | no | Non-PII reason summary. |

### ActorRef

| Field | Type | Required | Description |
|---|---|---|---|
| `actorType` | enum | yes | `SYSTEM`, `OPERATIONS`, `CUSTOMER_SERVICE`, or `USER` depending on command. |
| `actorId` | string | yes | Service actor, operator, customer-service, or user reference. |

### RecoveryCaseMapping

| Field | Type | Required | Description |
|---|---|---|---|
| `recoveryTriggerStatus` | enum | yes | `PENDING`, `OPENED`, or `FAILED` after a protected miss; `NOT_REQUIRED` is omitted from the object. |
| `disruptionId` | string | no | Disruption Recovery report ID (`drp-<uuid>`) from the response. |
| `incidentId` | string | no | Incident ID from the response. |
| `caseIds` | array[string] | yes | Recovery case IDs returned by Disruption Recovery. |
| `outboundIdempotencyKey` | string | yes | Persisted UUID-v7 key used for `POST /api/v1/disruptions`. |
| `openedAt` | RFC3339 UTC | no | Time the response was accepted. |
| `failureReason` | string | no | Sanitized failure reason if the outbound call failed. |

## Endpoints

### Create Transfer Plan

**POST** `/api/v1/transfer-plans`

**Idempotency:** REQUIRED (`Idempotency-Key` UUID-v7-shaped header).

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `itineraryRef` | string | yes | Trip Planning itinerary reference. |
| `planningSnapshotVersion` | integer | yes | Version/hash of the itinerary snapshot being evaluated. |
| `journeyOrderId` | string | no | Order ID when evaluating a purchased itinerary. |
| `travelerRefs` | array[string] | yes | Traveler references. |
| `connectionIntents` | array[object] | no | Optional caller-supplied transfer hints; ignored fields are not persisted. |
| `expiresAt` | RFC3339 UTC | no | Planning/offer expiry. |

**Response (201):** `TransferPlan` resource.

**Domain effects:** emits `TransferPlanCreated`; the service may synchronously
enter `EVALUATING` and emit `TransferPlanEvaluated` when itinerary and MCT data
are available.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Evaluate Transfer Plan

**POST** `/api/v1/transfer-plans/{transferPlanId}/evaluate`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `planningSnapshotVersion` | integer | yes | Itinerary snapshot version to evaluate. |
| `asOf` | RFC3339 UTC | no | Evaluation time; defaults to server time. |
| `reason` | string | no | Non-PII reason for manual/ops re-evaluation. |

**Response (200):** `TransferPlan` resource after evaluation.

**Domain effects:** emits `TransferPlanEvaluated` and one
`TransferRiskEvaluated` per registered connection whose risk changed.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Transfer Plan

**GET** `/api/v1/transfer-plans/{transferPlanId}`

**Response (200):** `TransferPlan` resource.

**Error codes:** `NOT_FOUND`

### Register Connection

**POST** `/api/v1/connections`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `transferPlanId` | string | yes | Owning transfer plan. |
| `itineraryRef` | string | yes | Source Trip Planning itinerary reference. |
| `journeyOrderId` | string | no | Purchased order when known. |
| `previousSegmentRef` | string | yes | Previous segment reference. |
| `nextSegmentRef` | string | yes | Next segment reference. |
| `travelerRefs` | array[string] | yes | Affected travelers. |
| `fromNodeRef` | string | yes | Arrival node/place reference. |
| `toNodeRef` | string | yes | Departure node/place reference. |
| `fromNodeType` | enum | yes | Arrival node type. |
| `toNodeType` | enum | yes | Departure node type. |
| `transferCategory` | enum | yes | Transfer category. |
| `contractId` | string | yes | Associated connection contract. |
| `window` | ConnectionWindow | yes | Planned transfer window. |

**Response (201):** `Connection` resource.

**Domain effects:** emits `ConnectionRegistered` and initial
`TransferRiskEvaluated`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

### Get Connection

**GET** `/api/v1/connections/{connectionId}`

**Response (200):** `Connection` resource.

**Error codes:** `NOT_FOUND`

### Report Segment Status

**POST** `/api/v1/segment-status-reports`

**Idempotency:** REQUIRED.

This endpoint is retained as the operations fallback runtime input channel for
delay, arrival, and cancellation facts. The primary event-driven path consumes
Fulfillment `SegmentDelayed`, `SegmentArrived`, and `SegmentCancelled` events and
maps them to the same application path with `sourceSystem=FULFILLMENT-EVENT`.
If the event-driven path reaches a Disruption Recovery downstream failure, the
consumer logs WARN and nacks for retry instead of applying HTTP `503` semantics.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `segmentRef` | string | yes | Segment being reported. |
| `reportType` | enum | yes | `DELAY`, `ARRIVAL`, or `CANCELLED`. |
| `reportedBy` | object | yes | ActorRef; `SYSTEM` and `OPERATIONS` are accepted. |
| `sourceSystem` | enum | yes | `OPERATIONS` or `ADMIN` for HTTP fallback; event-mapped reports use `FULFILLMENT-EVENT`. |
| `sourceRecordId` | string | yes | Source record ID for replay detection. |
| `observedAt` | RFC3339 UTC | yes | Observation time. |
| `estimatedArrivalAt` | RFC3339 UTC | for `DELAY` | Updated ETA. |
| `actualArrivalAt` | RFC3339 UTC | for `ARRIVAL` | Actual arrival. |
| `cancelledAt` | RFC3339 UTC | for `CANCELLED` | Cancellation time. |
| `reason` | string | no | Non-PII reason. |

**Response (202):**

| Field | Type | Description |
|---|---|---|
| `report` | SegmentStatusReport | Accepted report. |
| `updatedConnections` | array[Connection] | Connections whose runtime state/risk changed. |

**Domain effects:** evaluates impacted connections. It may emit
`TransferRiskEvaluated`, `TransferAtRisk`, `ConnectionMissed`,
`ConnectionRecovered`, or `ConnectionRecoveryFailed`. For protected misses it may
also call Disruption Recovery as described in [Outbound Disruption Recovery
command](#outbound-disruption-recovery-command).

**Error codes:** `VALIDATION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`


### Reaccommodate Connection

**POST** `/api/v1/connections/{connectionId}/reaccommodate`

**Idempotency:** REQUIRED (`Idempotency-Key` UUID-v7-shaped header). Disruption
Recovery folds `disruption-recovery:reaccommodation:<caseId>:<optionId>:<connectionId>`
into the UUID-v7 key and reuses it on retry. Replays with the same body return
the original `200` response; reuse with a different body returns
`IDEMPOTENCY_KEY_REUSED` (422).

This endpoint is called by Disruption Recovery after a user or customer-service
actor selects a scoped `REACCOMMODATION` option. The request does not perform real
rebooking in this wave; `replacementWindow` is the operations/system-declared
recovery input carried in the option parameters.

**Request:** `ReaccommodateConnectionRequest`

**Response (200):**

| Field | Type | Description |
|---|---|---|
| `connection` | Connection | Original connection after transition to `RECOVERED`. |
| `replacementConnection` | Connection | Newly registered replacement connection. |
| `caseId` | string | Disruption Recovery case ID applied. |
| `reaccommodatedAt` | RFC3339 UTC | Transition timestamp. |

**Domain effects:** validates that the original connection exists, is not
`RECOVERED` or `INVALIDATED`, and has a stored recovery mapping containing
`caseId`. It registers a replacement `Connection` using the original connection's
plan/order/traveler/contract metadata plus the declared `replacementWindow`, sets
`replacementOfConnectionId` on the replacement, sets `replacementConnectionId` on
the original, transitions the original connection to `RECOVERED`, and emits
`ConnectionRecovered`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`, `UNAVAILABLE`

- `NOT_FOUND` is returned when `connectionId` does not identify a known
  connection.
- `PRECONDITION_FAILED` (412) is returned when the connection is already
  `RECOVERED`, is `INVALIDATED`, is otherwise not recoverable, or `caseId` is not
  in the stored recovery mapping for the connection.
- `DOMAIN_RULE_VIOLATION` (422) is returned when `replacementWindow` is missing,
  has invalid RFC3339 UTC timestamps, or violates aggregate invariants such as
  `nextDepartureAt` not being after `plannedArrivalAt`.

### Propose Connection Contract

**POST** `/api/v1/connection-contracts`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `connectionId` | string | yes | Connection receiving this contract option. |
| `contractType` | enum | yes | Contract type. |
| `responsibleParty` | enum | yes | `PLATFORM`, `SUPPLIER`, `PLATFORM_ASSISTANCE`, or `TRAVELER`. |
| `coverageSummary` | string | yes | Safe display summary. |
| `disclosureVersion` | string | yes | User-visible disclosure copy version. |
| `termsSnapshotRef` | string | no | Immutable terms snapshot. |

**Response (201):** `ConnectionContract` resource.

**Domain effects:** emits `ConnectionContractProposed`.

**Error codes:** `VALIDATION_FAILED`, `NOT_FOUND`, `CONFLICT`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Confirm Connection Contract

**POST** `/api/v1/connection-contracts/{connectionContractId}/confirm`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `acceptedByRef` | string | yes | Offer/order/user/system actor that accepted the contract. |
| `acceptedVersion` | string | yes | Disclosure/terms version accepted. |
| `confirmedAt` | RFC3339 UTC | no | Acceptance time; defaults to server time. |

**Response (200):** `ConnectionContract` resource in `CONFIRMED` status.

**Domain effects:** emits `ConnectionContractConfirmed`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Withdraw Connection Contract

**POST** `/api/v1/connection-contracts/{connectionContractId}/withdraw`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `withdrawnBy` | object | yes | ActorRef for the operation. |
| `reason` | string | yes | Non-PII withdrawal reason. |

**Response (200):** `ConnectionContract` resource in `VOIDED` or `REJECTED`
status depending on previous state.

**Domain effects:** emits `ConnectionContractWithdrawn`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Create MCT Rule

**POST** `/api/v1/mct-rules`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `fromNodeType` | enum | yes | Arrival node type. |
| `toNodeType` | enum | yes | Departure node type. |
| `transferCategory` | enum | yes | Transfer category. |
| `minimumMinutes` | integer | yes | Positive required minutes. |
| `conditions` | object | yes | Structured condition flags; no PII. |
| `validFrom` | RFC3339 UTC | yes | Effective start. |
| `validUntil` | RFC3339 UTC | no | Effective end. |

**Response (201):** `MinimumConnectionTimeRule` resource in `DRAFT` status.

**Domain effects:** emits `MctRuleCreated`.

**Error codes:** `VALIDATION_FAILED`, `CONFLICT`, `DOMAIN_RULE_VIOLATION`,
`IDEMPOTENCY_KEY_REUSED`

### Update MCT Rule Draft

**PATCH** `/api/v1/mct-rules/{mctRuleId}`

**Idempotency:** REQUIRED.

Only `DRAFT` or `VALIDATED` rules may be updated. Published versions are
immutable; changing a published rule requires creating a new version.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `minimumMinutes` | integer | no | Replacement positive minutes. |
| `conditions` | object | no | Replacement non-PII conditions. |
| `validFrom` | RFC3339 UTC | no | Replacement effective start. |
| `validUntil` | RFC3339 UTC | no | Replacement effective end. |

**Response (200):** Updated `MinimumConnectionTimeRule`.

**Domain effects:** no bus event is required until publish/retire in this wave.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Publish MCT Rule

**POST** `/api/v1/mct-rules/{mctRuleId}/publish`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `publishedBy` | object | yes | ActorRef for the operation. |
| `publishReason` | string | yes | Non-PII reason. |

**Response (200):** `MinimumConnectionTimeRule` in `PUBLISHED` status.

**Domain effects:** emits `MctRulePublished`. The published rule version is
immutable.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`

### Retire MCT Rule

**POST** `/api/v1/mct-rules/{mctRuleId}/retire`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `retiredBy` | object | yes | ActorRef for the operation. |
| `retireReason` | string | yes | Non-PII reason. |
| `retiredAt` | RFC3339 UTC | no | Retire time; defaults to server time. |

**Response (200):** `MinimumConnectionTimeRule` in `RETIRED` status.

**Domain effects:** emits `MctRuleRetired`.

**Error codes:** `NOT_FOUND`, `PRECONDITION_FAILED`,
`DOMAIN_RULE_VIOLATION`, `IDEMPOTENCY_KEY_REUSED`


### Create Transfer Risk Policy

**POST** `/api/v1/risk-policies`

**Idempotency:** REQUIRED.

**Request:**

| Field | Type | Required | Description |
|---|---|---|---|
| `version` | string | yes | Immutable operator version label. |
| `thresholds.tightMinutes` | integer | yes | Tight buffer minute boundary. |
| `thresholds.atRiskMinutes` | integer | yes | At-risk buffer minute boundary. |
| `createdBy` | ActorRef | yes | Operations actor. |

**Response (201):** `TransferRiskPolicy` in `DRAFT` status.

### Activate Transfer Risk Policy

**POST** `/api/v1/risk-policies/{riskPolicyId}/activate`

**Idempotency:** REQUIRED. Activates the policy, retires any previously active
policy, and emits `RiskPolicyActivated`. Active versions are not mutable; create
and activate a new policy for changes. Retired policies cannot be reactivated.

**Request:** `activatedBy` ActorRef and optional `activateReason`.

**Response (200):** Active `TransferRiskPolicy`.

### Get Active Transfer Risk Policy

**GET** `/api/v1/risk-policies/active`

**Response (200):** Active `TransferRiskPolicy`, or the built-in fallback shape
with `version=builtin-v1` and `builtin=true` when no active policy exists.

### List MCT Rules

**GET** `/api/v1/mct-rules?fromNodeType=STATION&toNodeType=STATION&transferCategory=SAME_STATION&status=PUBLISHED&limit=20&offset=0`

**Query parameters:**

| Parameter | Type | Required | Description |
|---|---|---|---|
| `fromNodeType` | enum | no | Optional node-type filter. |
| `toNodeType` | enum | no | Optional node-type filter. |
| `transferCategory` | enum | no | Optional category filter. |
| `status` | enum | no | Optional rule status filter. |
| `limit` | integer | no | Pagination limit, default 20, max 100. |
| `offset` | integer | no | Pagination offset, default 0. |

**Response (200):** Paginated response with `items`, `total`, `limit`, and
`offset`, where each item is a `MinimumConnectionTimeRule`.

**Error codes:** `VALIDATION_FAILED`

## Outbound Disruption Recovery command

When a `Connection` transitions to `MISSED` and `contractType` is `PROTECTED` or
`SUPPLIER_PROTECTED`, Transfer Management sends:

**POST** `/api/v1/disruptions` on Disruption Recovery

**Headers:**

| Header | Required | Description |
|---|---|---|
| `Idempotency-Key` | yes | Persisted UUID-v7 key folded from `connectionId`, `missedAt`, connection version, and `journeyOrderId`. |
| `X-Correlation-Id` | yes | Existing `corr-<uuid-v7>` from the segment report or a generated correlation ID. |

**Request body mapping:**

| Field | Value |
|---|---|
| `disruptionType` | `MISSED_CONNECTION` |
| `scheduledServiceRef` | Omitted unless the next segment has a scheduled-service reference in the itinerary snapshot. |
| `segmentRef` | `nextSegmentRef` |
| `serviceDate` | Operating date of the next segment. |
| `evidence.evidenceRef` | `connectionId` or timeline evidence reference. |
| `evidence.sourceSystem` | `TRANSFER_MANAGEMENT` |
| `evidence.sourceRecordId` | `ConnectionMissed` envelope `eventId` or deterministic missed-transition reference. |
| `evidence.summary` | Sanitized missed-connection summary with no unmasked PII. |
| `evidence.occurredAt` | `missedAt` |
| `affectedOrderIds` | Array containing `journeyOrderId`. |
| `reportedBy.actorType` | `SYSTEM` |
| `reportedBy.actorId` | `transfer-management` |

**Response handling:** Transfer Management persists `disruption.disruptionId`,
`incident.incidentId`, and each `recoveryCases[].caseId` in `RecoveryCaseMapping`.
Subsequent `RecoveryCompleted` or `RecoveryFailed` events from
`events:disruption-recovery` are matched by `caseId`. For `REACCOMMODATION`, Transfer Management is also the executor; when it later consumes the `RecoveryCompleted` emitted as a result of its own `reaccommodate` response, it MUST treat the already-`RECOVERED` connection and matching `caseId` as an idempotent no-op rather than a failure or duplicate event.

**Failure handling:** downstream unavailability records
`recoveryTriggerStatus=FAILED` and a sanitized `failureReason`; retries reuse the
same outbound idempotency key.

## Deferred behavior

- TransferRiskPolicy simulation and future richer policy dimensions are deferred;
  this wave activates create/activate/current for runtime evaluation.
- Place Network walking/access-time weights remain deferred because the current
  Place Network contract exposes no such fields; Transfer Management only reads
  current node/place identity and node created timestamp for `placeGraphVersion`.
- Notification, Reporting, Customer Service, Offer Management, and Journey Order
  consumption of Transfer Management events is documented as intended but not
  registered as active downstream consumption in this wave.
- `REACCOMMODATION` is active only for Transfer Management system-originated `MISSED_CONNECTION` recovery cases. Other disruption types and sources remain deferred. The same implementation wave MUST update Transfer Management and Disruption Recovery code plus e2e 17/19 assertions for the WAIT-plus-REACCOMMODATION user-choice behavior.
