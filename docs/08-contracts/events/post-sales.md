# Post Sales — Events & Commands

Last updated: 2026-07-04

## Published Events

### PostSalesCaseOpened

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | fare-pricing, journey-order |
| **Trigger** | `OpenPostSalesCase` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Canonical case ID (`psc-<uuid>`). |
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order ID. |
| `caseType` | enum | yes | `CANCELLATION`, `REFUND`, `CHANGE`, `REBOOK`, `COMPENSATION`. |
| `scope` | object | yes | Affected order items, segments, travelers, entitlements. |
| `reasonCode` | string | yes | Business reason for the case. |
| `actorRef` | string | yes | Who requested the case. |

### PostSalesRequested

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | fare-pricing |
| **Trigger** | `RequestPostSales` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Canonical case ID (`psc-<uuid>`). |
| `orderId` | `JourneyOrderId` | yes | Parent order ID. |
| `requestType` | enum | yes | `CANCELLATION`, `REFUND_BY_RULE`, `CHANGE`. |
| `requestedAt` | RFC3339 UTC | yes | Request timestamp. |

### PostSalesEligibilityEvaluated

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | fare-pricing, notification |
| **Trigger** | `EvaluatePostSalesEligibility` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `eligible` | boolean | yes | Whether the request is eligible. |
| `reasonCode` | string | yes | Eligibility reason or blocking reason. |
| `ruleSnapshotRef` | string | no | Reference to the Fare & Pricing rule evaluation snapshot. |
| `ruleVersion` | string | no | Rule version used for evaluation. |

### PostSalesDecisionQuoted

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | notification |
| **Trigger** | `QuotePostSalesDecision` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `decisionKind` | enum | yes | `REFUND`, `CHANGE`, `CANCELLATION`, `COMPENSATION`. |
| `eligible` | boolean | yes | Whether the quoted decision is eligible. |
| `ruleSnapshotRef` | string | yes | Reference to the frozen rule evaluation snapshot. |

### PostSalesApproved

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | entitlement-ticketing, capacity-availability, payment, booking-orchestration |
| **Trigger** | Post-sales case approved for execution. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `approvedActions` | object | yes | Actions to execute. |

**`approvedActions` shape (normative):**

| Field | Type | Required | Description |
|---|---|---|---|
| `decisionKind` | enum | yes | `REFUND`, `CHANGE`, `COMPENSATION`. |
| `approvalRef` | string | yes | Reference to the authorizing approval. |
| `refund` | object | for REFUND | `{orderId, amount: Money, paymentIntentId?}` — payment resolves the intent by `orderId` (its `businessRef`) when `paymentIntentId` is absent. |
| `steps` | object[] | yes | Execution steps. Each step has `type` plus type-specific fields: `VOID_ENTITLEMENT {entitlementId, reason, policy}`, `RELEASE_CAPACITY {segmentBookingId}`. |

Execution completion is choreographed: `EntitlementVoided` →
booking-orchestration cancels the segment booking
(`SegmentBookingCancelled` carries `capacityHoldId`) →
capacity-availability releases the hold (`CapacityReleased` carries
`references.segmentBookingRef`) → post-sales marks the case applied
(`PostSalesApplied`).

### PostSalesExecutionStarted

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | notification |
| **Trigger** | `StartPostSalesExecution` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderedSteps` | `PostSalesStepType[]` | yes | Ordered list of execution step types. |
| `approvalRef` | string | yes | Reference to the approval that authorized execution. |

### PostSalesApplied

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | journey-order, notification |
| **Trigger** | All post-sales execution steps completed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `resultSummary` | object | yes | Summary of executed actions. |

### PostSalesFailed

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | journey-order, notification, admin-audit |
| **Trigger** | Unrecoverable step failure or manual termination. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `reason` | string | yes | Failure reason. |
| `failedStepRef` | string | no | Reference to the step that failed. |

### ChangeApplied

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | journey-order, entitlement-ticketing, notification |
| **Trigger** | Change execution completed successfully. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `oldEntitlementRef` | string | yes | Reference to the voided old entitlement. |
| `newEntitlementRef` | string | yes | Reference to the issued new entitlement. |
| `changeOfferRef` | string | yes | Reference to the change offer used. |

## Accepted Commands

### OpenPostSalesCase

| Field | Description |
|---|---|
| **Sender** | journey-order, customer-service |
| **Target Aggregate** | PostSalesCase |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `journeyOrderId` | `JourneyOrderId` | yes | Parent order ID. |
| `caseType` | enum | yes | Type of post-sales case. |
| `scope` | object | yes | Affected scope. |
| `reasonCode` | string | yes | Business reason. |
| `actorRef` | string | yes | Requester reference. |
| `idempotencyKey` | `IdempotencyKey` | yes | Idempotency key. |

### EvaluatePostSalesEligibility

| Field | Description |
|---|---|
| **Sender** | post-sales (internal) |
| **Target Aggregate** | PostSalesCase |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `eligible` | boolean | yes | Eligibility result. |
| `reasonCode` | string | yes | Reason code. |
| `ruleSnapshotRef` | string | no | Rule evaluation snapshot reference. |
| `ruleVersion` | string | no | Rule version. |

### QuotePostSalesDecision

| Field | Description |
|---|---|
| **Sender** | post-sales (internal) |
| **Target Aggregate** | PostSalesDecision |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `decision` | object | yes | Decision snapshot with amounts and eligibility. |

### ApprovePostSalesCase

| Field | Description |
|---|---|
| **Sender** | customer-service, post-sales (auto) |
| **Target Aggregate** | PostSalesCase |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `approvalRef` | string | yes | Approval reference. |

### StartPostSalesExecution

| Field | Description |
|---|---|
| **Sender** | post-sales (internal) |
| **Target Aggregate** | PostSalesCase / PostSalesExecutionPlan |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |

### ApplyPostSalesResult

| Field | Description |
|---|---|
| **Sender** | post-sales (internal) |
| **Target Aggregate** | PostSalesCase |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `resultSummary` | string | yes | Summary of the applied result. |
