# Customer Service — Events & Commands

Last updated: 2026-07-04

## Published Events

### SupportCaseOpened

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting, admin-audit |
| **Trigger** | `OpenSupportCase` command processed with valid requester, channel, and description. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Canonical support case ID (`sc-<uuid>`). |
| `requesterRef` | `AccountRef` | yes | Reference to the requester (`tvl-<uuid>` or `usr-<uuid>`). |
| `channel` | enum | yes | `APP`, `WEB`, `PHONE`, `IM`, `EMAIL`, `IN_APP_MESSAGE`, `BOT`, `OPERATOR_CONSOLE`. |
| `classification` | string | no | Initial classification code if provided. |
| `priority` | enum | no | `LOW`, `NORMAL`, `HIGH`, `URGENT`. |
| `description` | string | yes | Case description from the requester. |
| `businessReferences` | object | no | References to business objects: `accountRef`, `journeyOrderId`, `paymentRef`, `postSalesCaseId`, `recoveryCaseId`. |

### EvidenceAttached

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | admin-audit |
| **Trigger** | `AttachEvidence` command processed with valid evidence reference and access level. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceId` | `EvidenceId` | yes | Canonical evidence ID (`evid-<uuid>`). |
| `caseId` | `SupportCaseId` | yes | Case the evidence is attached to. |
| `evidenceType` | enum | yes | `SCREENSHOT`, `CALL_RECORDING`, `CHAT_TRANSCRIPT`, `EMAIL`, `CHANNEL_RECEIPT`, `PROVIDER_SUMMARY`, `DOCUMENT`, `OTHER`. |
| `reference` | string | yes | External reference URI or path. |
| `summary` | string | yes | Human-readable summary of the evidence. |
| `accessLevel` | enum | yes | `PUBLIC`, `INTERNAL`, `SENSITIVE`, `RESTRICTED`. |
| `attachedBy` | `OperatorRef` | yes | Operator who attached the evidence (`op-<uuid>`). |

### SupportCaseClassified

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `ClassifySupportCase` command processed with classification and priority. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case being classified. |
| `classification` | string | yes | Classification code. |
| `priority` | enum | yes | `LOW`, `NORMAL`, `HIGH`, `URGENT`. |
| `classifiedBy` | `OperatorRef` | yes | Operator who classified the case. |

### SupportCaseAssigned

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `AssignSupportCase` command processed with queue or operator assignment. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case being assigned. |
| `ownerQueue` | string | yes | Target queue name. |
| `assignedTo` | `OperatorRef` | no | Specific operator assigned (`op-<uuid>`). |
| `assignedBy` | `OperatorRef` | yes | Operator who performed the assignment. |

### ManualActionRequested

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | admin-audit, notification |
| **Trigger** | `RequestManualAction` command processed for a controlled manual action. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | `ManualActionId` | yes | Canonical manual action ID (`ma-<uuid>`). |
| `caseId` | `SupportCaseId` | yes | Case requesting the action. |
| `targetDomain` | enum | yes | `journey-order`, `payment`, `post-sales`, `disruption-recovery`, `account`, `notification`. |
| `commandType` | string | yes | Target domain command type (e.g. `ManualPaymentActionRequested`). |
| `operatorRef` | `OperatorRef` | yes | Operator requesting the action (`op-<uuid>`). |
| `reason` | string | yes | Reason for the manual action. |
| `evidenceRefs` | `EvidenceId[]` | no | References to attached evidence. |
| `description` | string | yes | Human-readable description of the action. |
| `requiresApproval` | boolean | yes | Whether four-eyes approval is required. |

### ManualActionResultRecorded

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `RecordActionOutcome` command processed with outcome result. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | `ManualActionId` | yes | Canonical manual action ID. |
| `caseId` | `SupportCaseId` | yes | Case the action belongs to. |
| `outcome` | enum | yes | `Succeeded`, `Failed`, `Rejected`, `Cancelled`. |
| `resultSummary` | string | yes | Summary of the outcome. |
| `approvalRef` | `ApprovalRef` | no | Approval reference if applicable (`aprv-<uuid>`). |

### SupportCaseEscalated

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `EscalateCase` command processed with target queue and reason. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case being escalated. |
| `targetQueue` | string | yes | Target escalation queue. |
| `reason` | string | yes | Escalation reason. |
| `escalatedBy` | `OperatorRef` | yes | Operator who escalated the case. |

### SupportCaseResolved

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `ResolveCase` command processed with resolution details. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case being resolved. |
| `summary` | string | yes | Resolution summary. |
| `resolutionCode` | string | yes | Resolution code (e.g. `PAYMENT_RETRY_SUCCESS`). |
| `resolvedBy` | `OperatorRef` | yes | Operator who resolved the case. |

### SupportCaseClosed

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `CloseCase` command processed with close reason. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case being closed. |
| `reason` | enum | yes | `RESOLVED`, `ESCALATED`, `DUPLICATE`, `NO_FURTHER_ACTION`, `CUSTOMER_CLOSED`. |
| `closedBy` | `OperatorRef` | yes | Operator who closed the case. |

### SupportCaseReopened

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | notification, reporting |
| **Trigger** | `ReopenCase` command processed when a closed or resolved case is reopened. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case being reopened. |
| `reason` | string | yes | Reason for reopening. |
| `requesterRef` | `AccountRef` | yes | Reference to the requester. |

### CaseTimelineEntryAppended

| Field | Description |
|---|---|
| **Producer** | customer-service |
| **Consumers** | reporting |
| **Trigger** | `AppendTimelineEntry` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entryId` | `TimelineEntryId` | yes | Canonical timeline entry ID (`tl-<uuid>`). |
| `caseId` | `SupportCaseId` | yes | Case the entry belongs to. |
| `eventTypeCode` | string | yes | Type code for the timeline event. |
| `visibility` | enum | yes | `CUSTOMER_VISIBLE`, `INTERNAL_ONLY`. |

## Accepted Commands

### OpenSupportCase

| Field | Description |
|---|---|
| **Sender** | API gateway / UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Unique case identifier (`sc-<uuid>`). |
| `requesterRef` | `AccountRef` | yes | Reference to the requester. |
| `channel` | enum | yes | Entry channel. |
| `classification` | string | no | Initial classification. |
| `priority` | enum | no | `LOW`, `NORMAL`, `HIGH`, `URGENT`. |
| `description` | string | yes | Case description. |
| `businessReferences` | object | no | Business object references. |
| `correlationId` | `CorrelationId` | yes | Business transaction correlation. |
| `causationId` | `CausationId` | no | Source command or event ID. |
| `openedAt` | RFC3339 UTC | yes | When the case was opened. |

### AttachEvidence

| Field | Description |
|---|---|
| **Sender** | Customer Service UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `evidenceId` | `EvidenceId` | yes | Unique evidence identifier (`evid-<uuid>`). |
| `caseId` | `SupportCaseId` | yes | Case to attach evidence to. |
| `evidenceType` | enum | yes | Type of evidence. |
| `reference` | string | yes | External reference. |
| `summary` | string | yes | Human-readable summary. |
| `accessLevel` | enum | yes | Access control level. |
| `attachedBy` | `OperatorRef` | yes | Attaching operator. |
| `correlationId` | `CorrelationId` | yes | Business transaction correlation. |
| `causationId` | `CausationId` | no | Source command or event ID. |
| `attachedAt` | RFC3339 UTC | yes | When attached. |

### RequestManualAction

| Field | Description |
|---|---|
| **Sender** | Customer Service UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | `ManualActionId` | yes | Unique action identifier (`ma-<uuid>`). |
| `caseId` | `SupportCaseId` | yes | Case requesting the action. |
| `targetDomain` | enum | yes | Target bounded context. |
| `commandType` | string | yes | Target command type. |
| `operatorRef` | `OperatorRef` | yes | Requesting operator. |
| `reason` | string | yes | Action reason. |
| `evidenceRefs` | `EvidenceId[]` | no | Evidence references. |
| `description` | string | yes | Action description. |
| `requiresApproval` | boolean | yes | Whether approval is required. |
| `correlationId` | `CorrelationId` | yes | Business transaction correlation. |
| `causationId` | `CausationId` | no | Source command or event ID. |
| `requestedAt` | RFC3339 UTC | yes | When requested. |

### RecordActionOutcome

| Field | Description |
|---|---|
| **Sender** | Customer Service UI / Admin & Audit |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | `ManualActionId` | yes | Action to record outcome for. |
| `outcome` | enum | yes | `Succeeded`, `Failed`, `Rejected`, `Cancelled`. |
| `resultSummary` | string | yes | Outcome summary. |
| `approvalRef` | `ApprovalRef` | no | Approval reference. |
| `recordedAt` | RFC3339 UTC | yes | When recorded. |

### EscalateCase

| Field | Description |
|---|---|
| **Sender** | Customer Service UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case to escalate. |
| `targetQueue` | string | yes | Target queue. |
| `reason` | string | yes | Escalation reason. |
| `escalatedBy` | `OperatorRef` | yes | Escalating operator. |
| `escalatedAt` | RFC3339 UTC | yes | When escalated. |

### ResolveCase

| Field | Description |
|---|---|
| **Sender** | Customer Service UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case to resolve. |
| `summary` | string | yes | Resolution summary. |
| `resolutionCode` | string | yes | Resolution code. |
| `resolvedBy` | `OperatorRef` | yes | Resolving operator. |
| `resolvedAt` | RFC3339 UTC | yes | When resolved. |

### CloseCase

| Field | Description |
|---|---|
| **Sender** | Customer Service UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case to close. |
| `reason` | enum | yes | Close reason. |
| `closedBy` | `OperatorRef` | yes | Closing operator. |
| `closedAt` | RFC3339 UTC | yes | When closed. |

### ReopenCase

| Field | Description |
|---|---|
| **Sender** | Customer Service UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `caseId` | `SupportCaseId` | yes | Case to reopen. |
| `reason` | string | yes | Reopen reason. |
| `requesterRef` | `AccountRef` | yes | Requester reference. |
| `reopenedAt` | RFC3339 UTC | yes | When reopened. |

### AppendTimelineEntry

| Field | Description |
|---|---|
| **Sender** | Customer Service UI / Internal |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `entryId` | `TimelineEntryId` | yes | Unique entry identifier (`tl-<uuid>`). |
| `caseId` | `SupportCaseId` | yes | Case for the timeline. |
| `eventType` | string | yes | Event type code. |
| `payload` | object | yes | Event payload data. |
| `visibility` | enum | yes | `CUSTOMER_VISIBLE`, `INTERNAL_ONLY`. |
| `occurredAt` | RFC3339 UTC | yes | When the event occurred. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| admin-audit | `ManualActionApproved`, `ManualActionRejected`, `ManualActionExecuted` | Action governance and outcome tracking. |
| journey-order | OrderSummary, OrderDetail, OrderTimeline | Case context for order-related issues. |
| payment | PaymentStatusView, RefundView, PaymentOperationTimeline | Case context for payment disputes. |
| post-sales | PostSalesCaseView, PostSalesExecutionTimeline | Case context for post-sales exceptions. |
| disruption-recovery | RecoveryCaseView, ManualRecoveryQueue | Case context for disruption recovery. |
| account | Account profile view | Requester identity verification. |
| notification | Delivery summary | Case communication tracking. |
