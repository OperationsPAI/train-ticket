# Admin & Audit — Events & Commands

Last updated: 2026-07-04

## Published Events

### OperatorRegistered

| Field | Description |
|---|---|
| **Producer** | admin-audit |
| **Consumers** | notification, reporting |
| **Trigger** | `RegisterOperator` command processed with valid operator profile. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `operatorId` | string | yes | Canonical operator ID (`op-<uuid>`). |
| `email` | string | yes | Operator email address. |
| `role` | enum | yes | Operator role (`ADMIN`, `OPERATOR`, `SUPER_OPERATOR`, `AUDITOR`, etc.). |
| `scopes` | string[] | yes | List of permission scopes assigned to the operator. |

### ManualActionRequested

| Field | Description |
|---|---|
| **Producer** | admin-audit |
| **Consumers** | notification, customer-service |
| **Trigger** | `RequestManualAction` command processed with valid target domain and reason. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Canonical manual action ID (`ma-<uuid>`). |
| `targetDomain` | string | yes | Target bounded context domain (e.g. `journey-order`, `payment`). |
| `targetCommand` | string | yes | Target domain command to execute (e.g. `RequestManualOrderAction`). |
| `businessRef` | string | yes | Business reference (e.g. order ID, payment ID). |
| `reasonCode` | string | yes | Reason code for the action (e.g. `CUSTOMER_REQUEST`). |
| `description` | string | yes | Human-readable description of the action. |
| `requestedByOperatorId` | string | yes | Operator who requested the action. |
| `requestedByDisplayName` | string | yes | Display name of the requesting operator. |
| `requiresApproval` | boolean | yes | Whether this action requires four-eyes approval. |

### ManualActionApproved

| Field | Description |
|---|---|
| **Producer** | admin-audit |
| **Consumers** | notification, target domain |
| **Trigger** | `ApproveManualAction` command processed with distinct approver. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Canonical manual action ID. |
| `targetDomain` | string | yes | Target bounded context domain. |
| `targetCommand` | string | yes | Target domain command to execute. |
| `businessRef` | string | yes | Business reference. |
| `approvedByOperatorId` | string | yes | Operator who approved the action. |
| `approvedByDisplayName` | string | yes | Display name of the approving operator. |

### ManualActionRejected

| Field | Description |
|---|---|
| **Producer** | admin-audit |
| **Consumers** | notification, customer-service |
| **Trigger** | `RejectManualAction` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Canonical manual action ID. |
| `targetDomain` | string | yes | Target bounded context domain. |
| `targetCommand` | string | yes | Target domain command. |
| `businessRef` | string | yes | Business reference. |
| `rejectedByOperatorId` | string | yes | Operator who rejected the action. |
| `reason` | string | yes | Rejection reason. |

### ManualActionExecuted

| Field | Description |
|---|---|
| **Producer** | admin-audit |
| **Consumers** | notification, reporting |
| **Trigger** | `ExecuteManualAction` command processed; target domain accepted or rejected the command. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Canonical manual action ID. |
| `targetDomain` | string | yes | Target bounded context domain. |
| `targetCommand` | string | yes | Target domain command. |
| `businessRef` | string | yes | Business reference. |
| `resultSummary` | string | yes | Execution result summary (includes failure prefix on rejection). |

### AuditEntryRecorded

| Field | Description |
|---|---|
| **Producer** | admin-audit |
| **Consumers** | reporting, risk-compliance |
| **Trigger** | `RecordAuditEntry` command processed. |

**Payload:**

| Field | Type | Required | Description |
|---|---|---|---|
| `entryId` | string | yes | Canonical audit entry ID (`aud-<uuid>`). |
| `actorId` | string | yes | Operator who performed the action. |
| `actorDisplayName` | string | yes | Display name of the actor. |
| `actionType` | string | yes | Type of action recorded (e.g. `ORDER_CANCELLATION`). |
| `resourceRef` | string | yes | Reference to the affected resource. |
| `resourceDomain` | string | yes | Domain of the affected resource. |
| `reasonCode` | string | yes | Reason code for the action. |
| `correlationId` | `CorrelationId` | yes | Business transaction correlation. |
| `resultSummary` | string | no | Summary of the action result. |
| `correctedEntryId` | string | no | If this entry corrects a previous entry, the original entry ID. |

## Accepted Commands

### RegisterOperator

| Field | Description |
|---|---|
| **Sender** | Admin UI / IAM |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `email` | string | yes | Operator email address. |
| `role` | enum | yes | Operator role. |
| `scopes` | PermissionScope[] | yes | Permission scopes assigned to the operator. |

### RequestManualAction

| Field | Description |
|---|---|
| **Sender** | Admin UI / Customer Service |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `targetDomain` | string | yes | Target bounded context domain. |
| `targetCommand` | string | yes | Target domain command. |
| `businessRef` | string | yes | Business reference. |
| `reasonCode` | string | yes | Reason code. |
| `description` | string | yes | Action description. |
| `requestedBy` | `OperatorRef` | yes | Requesting operator. |
| `requiresApproval` | boolean | yes | Whether four-eyes approval is required. |

### ApproveManualAction

| Field | Description |
|---|---|
| **Sender** | Admin UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Manual action ID to approve. |
| `approver` | `OperatorRef` | yes | Approving operator (must be distinct from requester). |

### RejectManualAction

| Field | Description |
|---|---|
| **Sender** | Admin UI |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Manual action ID to reject. |
| `rejectedBy` | `OperatorRef` | yes | Rejecting operator. |
| `reason` | string | yes | Rejection reason. |

### ExecuteManualAction

| Field | Description |
|---|---|
| **Sender** | Admin & Audit |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `manualActionId` | string | yes | Manual action ID to execute. |
| `resultSummary` | string | yes | Execution result summary. |

### RecordAuditEntry

| Field | Description |
|---|---|
| **Sender** | Any bounded context |
| **Payload** | |

| Field | Type | Required | Description |
|---|---|---|---|
| `actorId` | string | yes | Actor performing the action. |
| `actorDisplayName` | string | yes | Actor display name. |
| `actionType` | string | yes | Action type. |
| `resourceRef` | string | yes | Resource reference. |
| `resourceDomain` | string | yes | Resource domain. |
| `reasonCode` | string | yes | Reason code. |
| `correlationId` | `CorrelationId` | yes | Business transaction correlation. |
| `resultSummary` | string | no | Result summary. |
| `correctedEntryId` | string | no | Original entry ID if this is a correction. |

## Cross-Context Dependencies

| Upstream Context | Consumed Data | Purpose |
|---|---|---|
| customer-service | Manual action requests, support case references | Action governance. |
| risk-compliance | Risk decision signals | High-risk action approval constraints. |
