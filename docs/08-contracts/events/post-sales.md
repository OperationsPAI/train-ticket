# Post Sales Events & Commands

Last updated: 2026-09-25.

Runtime baseline: Helm revision 279.
Remaining work is tracked in [TODO](../../TODO.md).

Capacity release handling selects only cases with an unrecorded release that are approved, executing, or applied changes needing release evidence.
Already recorded releases and other terminal cases do not reacquire the order lock.
An actionable release still reloads the case under the order lock before saving its result.
Wallet compensation cases preserve their original capacity and are skipped by capacity release application.

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
| `requestType` | enum | yes | `CANCELLATION`, `REFUND_BY_RULE`, `CHANGE`, `COMPENSATION`. |
| `requestedAt` | RFC3339 UTC | yes | Request timestamp. |

`caseType=COMPENSATION` maps to `requestType=COMPENSATION`.

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
| `refund` | object | for REFUND | `{orderId, amount: Money, paymentIntentId?}`; payment resolves the intent by `orderId` (its `businessRef`) when `paymentIntentId` is absent. |
| `compensation` | object | for COMPENSATION | `{method: "WALLET_CREDIT", amount: Money, caseId}` with the approved decision amount and canonical case ID. |
| `steps` | object[] | yes | Empty for `COMPENSATION` and `CHANGE`. Refund execution uses `VOID_ENTITLEMENT {entitlementId, reason, policy}` and `RELEASE_CAPACITY {segmentBookingId}`. |

For refund execution, `EntitlementVoided` triggers booking cancellation, `SegmentBookingCancelled` carries `capacityHoldId` for Hold release, and `CapacityReleased` carries `references.segmentBookingRef` for Post Sales completion.
An operator can redrive a historical case that remains internally `EXECUTING` by calling the approval endpoint with a new HTTP idempotency key.
Post Sales reconstructs this event from the immutable decision and current persisted execution inputs; it does not approve the case again.
The first redrive persists one stable event ID and envelope metadata, and later repeated or concurrent redrives reuse that identity.
Payment deduplicates the refund by canonical case ID, or by `caseId:paymentIntentId` for an allocated split refund.
Entitlement Ticketing applies the same void action with the same business case reference and emits no second void result when the entitlement is already voided.
An `APPLIED` case is not redriven.
For wallet compensation, `approvedActions.decisionKind=COMPENSATION` authorizes the recorded wallet credit amount and supplies an empty `steps` array.
Payment skips refund processing for this decision kind, and the approval contains no ticket void or capacity release action.
Original tickets, seat reservations, order and captured payment are preserved.
Wallet issuance is requested through `POST /api/v1/benefits` by `execute-compensation`.
Its request uses `issuanceSource=POST_SALES_COMP`, `benefitType=COMPENSATION_CREDIT`, `balanceType=PROMOTION_CREDIT` and the canonical `caseId`.
The current request fixes a 30 day validity window when `compensationOperations.request` is created and retains those timestamps across retries.
The HTTP evaluation response and case decision expose the approved value as `compensationAmount` and expose zero as `refundableAmount`.

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

For wallet compensation, `orderedSteps` is `ISSUE_COMPENSATION_CREDIT`, followed by `APPLY_RESULT`.
Historical `EXECUTING` redrive reconstructs this event from the persisted step order and reuses the stable redrive event identity stored in the case snapshot.
It does not restart, clear or reorder execution steps.
The active executor requires the approved plan to contain `ISSUE_COMPENSATION_CREDIT` before it can issue wallet credit.
Approval starts internal status `EXECUTING`; the HTTP representation remains `APPROVED` until the case completes.
Execution records the full request, checked wallet issuance response and verification result in `compensationOperations.request`, `issued` and `verified`.
Verification includes `originalTicketsPreserved`, `originalPaymentPreserved`, `paymentIntentId`, `capturedAmount`, approved `amount` and `benefitId`.
The executor reads the original payment by order business reference and requires `CAPTURED` with zero `refundedAmount`, in addition to zero refunds for the compensation case.
Once verification exists, `completeCompensation` records both execution steps as `SUCCEEDED` in order, then the domain applies the case.
The credit step references the issued `benefitId`, or `zero-approved-compensation` for a zero approved amount, and `APPLY_RESULT` references the original order.
Zero amounts have no wallet issuance or `issued` record and retain an empty `verified.benefitId`.

### PostSalesApplied

| Field | Description |
|---|---|
| **Producer** | post-sales |
| **Consumers** | journey-order, notification |
| **Trigger** | The completion condition for the case type is satisfied. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `PostSalesCaseId` | yes | Case ID. |
| `orderId` | `JourneyOrderId` | yes | Parent order. |
| `resultSummary` | object | yes | Summary of executed actions. |
| `caseType` | string | yes | Executed case type. `COMPENSATION` identifies the completed wallet compensation case; `CHANGE` has already applied its verified replacement order. |
| `scope` | object | yes | Current traveler and segment references affected by the result. |
| `retainedFee` | Money | yes | Assessed fee from the decision snapshot. Journey Order records refund fees once per case and skips `COMPENSATION` results. |
| `compensation` | object or null | for COMPENSATION | Recorded `compensationOperations.issued` wallet response, including `benefitId`, account, case and `issuedAmount`; null for a zero approved amount. |

Ordinary refund and cancellation cases can publish this event after all scoped capacity releases are recorded.
The event alone does not establish Payment refund settlement.
Change completion follows recorded order application and required original capacity release.
For `COMPENSATION`, this event follows verified wallet execution and successful completion of `ISSUE_COMPENSATION_CREDIT` and `APPLY_RESULT`.
Journey Order, Finance Settlement and Invoicing skip their refund result processing for this case type.
The original order, invoice basis, tickets, seat reservations and captured payment are preserved by this compensation flow.
Wallet's `BenefitIssued` supplies the compensation issuance cost and ledger delta for financial attribution.
Compensation approval and completion do not add another issuance cost.
An execution call on an already `APPLIED` or `FAILED` compensation case returns its existing state without publishing another completion event.

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

An unavailable replacement emits `reason=CHANGE_CAPACITY_UNAVAILABLE` with `failedStepRef=replacement-reservation` after verified compensation.
The case retains `capacity-rejected` and `capacity-compensation-completed` operation records.
Original tickets remain issued, replacement Holds are terminal and any captured difference payment has a settled full refund before this event is published.
An uncaptured terminated difference payment uses `CHANGE_PAYMENT_TERMINATED` after its own verified cancellation path.
An expired target Offer before replacement operations uses `CHANGE_OFFER_EXPIRED` with `failedStepRef=target-offer` after original ticket validation.

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
