/**
 * Customer Service domain foundation — TypeScript
 *
 * Aggregates:
 *   SupportCase          — lifecycle of a customer support case
 *   EvidenceRef          — reference to evidence attached to a case
 *   ManualActionRequest  — request for a controlled manual action forwarded to Admin & Audit
 *   CaseTimeline         — append-only timeline of case events
 *
 * Key invariants:
 *   1. A support case only references business objects (order, payment,
 *      entitlement, post-sales case) — it never writes their state.
 *   2. Manual action requests are forwarded to Admin & Audit for controlled
 *      execution; the case records the outcome.
 *   3. Case timeline entries are append-only.
 *   4. Case closure requires either a resolution record or explicit escalation.
 */

import crypto from "node:crypto";

// ─── Error ────────────────────────────────────────────────────────────────────

export class DomainError extends Error {
  public readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.name = "DomainError";
    this.code = code;
  }
}

// ─── ID types ──────────────────────────────────────────────────────────────────

export type SupportCaseId = string;       // "sc-<uuid>"
export type EvidenceId = string;          // "evid-<uuid>"
export type ManualActionId = string;      // "ma-<uuid>"
export type TimelineEntryId = string;     // "tl-<uuid>"
export type AccountRef = string;          // "tvl-<uuid>" or "usr-<uuid>"
export type JourneyOrderId = string;      // "ord-<uuid>"
export type PaymentRef = string;          // "pi-<uuid>"
export type PostSalesCaseId = string;     // "psc-<uuid>"
export type RecoveryCaseId = string;      // "dc-<uuid>"
export type OperatorRef = string;         // "op-<uuid>"
export type ApprovalRef = string;         // "aprv-<uuid>"
export type EscalationRef = string;       // "escl-<uuid>"
export type EventId = string;             // "evt-<uuid>"
export type CorrelationId = string;       // "corr-<uuid>"
export type CausationId = string;         // "cmd-<uuid>" or "evt-<uuid>"

// ─── Enums ─────────────────────────────────────────────────────────────────────

export type SupportCaseStatus =
  | "Opened"
  | "Classifying"
  | "Assigned"
  | "InProgress"
  | "WaitingCustomer"
  | "WaitingExternal"
  | "Escalated"
  | "Resolved"
  | "Closed"
  | "Reopened";

export type CaseChannel =
  | "APP"
  | "WEB"
  | "PHONE"
  | "IM"
  | "EMAIL"
  | "IN_APP_MESSAGE"
  | "BOT"
  | "OPERATOR_CONSOLE";

export type CasePriority =
  | "LOW"
  | "NORMAL"
  | "HIGH"
  | "URGENT";

export type CaseClassification = string; // free-form classification code

export type ManualActionStatus =
  | "Draft"
  | "Requested"
  | "Approved"
  | "Dispatched"
  | "WaitingExternal"
  | "Succeeded"
  | "Failed"
  | "Rejected"
  | "Cancelled";

export type ManualActionTargetDomain =
  | "journey-order"
  | "payment"
  | "post-sales"
  | "disruption-recovery"
  | "account"
  | "notification";

export type EvidenceType =
  | "SCREENSHOT"
  | "CALL_RECORDING"
  | "CHAT_TRANSCRIPT"
  | "EMAIL"
  | "CHANNEL_RECEIPT"
  | "PROVIDER_SUMMARY"
  | "DOCUMENT"
  | "OTHER";

export type EvidenceAccessLevel =
  | "PUBLIC"
  | "INTERNAL"
  | "SENSITIVE"
  | "RESTRICTED";

export type TimelineVisibility =
  | "CUSTOMER_VISIBLE"
  | "INTERNAL_ONLY";

export type CloseReason =
  | "RESOLVED"
  | "ESCALATED"
  | "DUPLICATE"
  | "NO_FURTHER_ACTION"
  | "CUSTOMER_CLOSED";

// ─── Value Objects ─────────────────────────────────────────────────────────────

export type BusinessReferences = Readonly<{
  accountRef?: AccountRef;
  journeyOrderId?: JourneyOrderId;
  paymentRef?: PaymentRef;
  postSalesCaseId?: PostSalesCaseId;
  recoveryCaseId?: RecoveryCaseId;
}>;

export type Resolution = Readonly<{
  summary: string;
  resolutionCode: string;
  resolvedAt: Date;
  resolvedBy: OperatorRef;
}>;

export type EscalationInfo = Readonly<{
  escalationRef: EscalationRef;
  targetQueue: string;
  reason: string;
  escalatedAt: Date;
  escalatedBy: OperatorRef;
}>;

// ─── Commands ──────────────────────────────────────────────────────────────────

export type OpenSupportCase = Readonly<{
  caseId: SupportCaseId;
  requesterRef: AccountRef;
  channel: CaseChannel;
  classification?: CaseClassification;
  priority?: CasePriority;
  description: string;
  businessReferences?: BusinessReferences;
  correlationId: CorrelationId;
  causationId?: CausationId;
  openedAt: Date;
}>;

export type ClassifySupportCase = Readonly<{
  caseId: SupportCaseId;
  classification: CaseClassification;
  priority: CasePriority;
  classifiedBy: OperatorRef;
  correlationId?: CorrelationId;
  causationId?: CausationId;
  classifiedAt: Date;
}>;

export type AssignSupportCase = Readonly<{
  caseId: SupportCaseId;
  ownerQueue: string;
  assignedTo?: OperatorRef;
  assignedBy: OperatorRef;
  correlationId?: CorrelationId;
  causationId?: CausationId;
  assignedAt: Date;
}>;

export type AttachEvidence = Readonly<{
  evidenceId: EvidenceId;
  caseId: SupportCaseId;
  evidenceType: EvidenceType;
  reference: string;
  summary: string;
  accessLevel: EvidenceAccessLevel;
  attachedBy: OperatorRef;
  correlationId: CorrelationId;
  causationId?: CausationId;
  attachedAt: Date;
}>;

export type RequestManualAction = Readonly<{
  manualActionId: ManualActionId;
  caseId: SupportCaseId;
  targetDomain: ManualActionTargetDomain;
  commandType: string;
  operatorRef: OperatorRef;
  reason: string;
  evidenceRefs?: readonly EvidenceId[];
  description: string;
  requiresApproval: boolean;
  correlationId: CorrelationId;
  causationId?: CausationId;
  requestedAt: Date;
}>;

export type RecordActionOutcome = Readonly<{
  manualActionId: ManualActionId;
  outcome: "Succeeded" | "Failed" | "Rejected" | "Cancelled";
  resultSummary: string;
  approvalRef?: ApprovalRef;
  recordedAt: Date;
}>;

export type EscalateCase = Readonly<{
  caseId: SupportCaseId;
  targetQueue: string;
  reason: string;
  escalatedBy: OperatorRef;
  correlationId?: CorrelationId;
  causationId?: CausationId;
  escalatedAt: Date;
}>;

export type ResolveCase = Readonly<{
  caseId: SupportCaseId;
  summary: string;
  resolutionCode: string;
  resolvedBy: OperatorRef;
  correlationId?: CorrelationId;
  causationId?: CausationId;
  resolvedAt: Date;
}>;

export type CloseCase = Readonly<{
  caseId: SupportCaseId;
  reason: CloseReason;
  closedBy: OperatorRef;
  correlationId?: CorrelationId;
  causationId?: CausationId;
  closedAt: Date;
}>;

export type ReopenCase = Readonly<{
  caseId: SupportCaseId;
  reason: string;
  requesterRef: AccountRef;
  correlationId?: CorrelationId;
  causationId?: CausationId;
  reopenedAt: Date;
}>;

export type AppendTimelineEntry = Readonly<{
  entryId: TimelineEntryId;
  caseId: SupportCaseId;
  eventType: string;
  payload: Readonly<Record<string, unknown>>;
  visibility: TimelineVisibility;
  occurredAt: Date;
}>;

// ─── Domain Events ─────────────────────────────────────────────────────────────

export type SupportCaseOpened = Readonly<{
  type: "SupportCaseOpened";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  requesterRef: AccountRef;
  channel: CaseChannel;
  classification?: CaseClassification;
  priority?: CasePriority;
  description: string;
  businessReferences?: BusinessReferences;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type EvidenceAttached = Readonly<{
  type: "EvidenceAttached";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  evidenceId: EvidenceId;
  caseId: SupportCaseId;
  evidenceType: EvidenceType;
  reference: string;
  summary: string;
  accessLevel: EvidenceAccessLevel;
  attachedBy: OperatorRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type SupportCaseClassified = Readonly<{
  type: "SupportCaseClassified";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  classification: CaseClassification;
  priority: CasePriority;
  classifiedBy: OperatorRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type SupportCaseAssigned = Readonly<{
  type: "SupportCaseAssigned";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  ownerQueue: string;
  assignedTo?: OperatorRef;
  assignedBy: OperatorRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type ManualActionRequested = Readonly<{
  type: "ManualActionRequested";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  manualActionId: ManualActionId;
  caseId: SupportCaseId;
  targetDomain: ManualActionTargetDomain;
  commandType: string;
  operatorRef: OperatorRef;
  reason: string;
  evidenceRefs: readonly EvidenceId[];
  description: string;
  requiresApproval: boolean;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type ManualActionResultRecorded = Readonly<{
  type: "ManualActionResultRecorded";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  manualActionId: ManualActionId;
  caseId: SupportCaseId;
  outcome: "Succeeded" | "Failed" | "Rejected" | "Cancelled";
  resultSummary: string;
  approvalRef?: ApprovalRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type SupportCaseEscalated = Readonly<{
  type: "SupportCaseEscalated";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  targetQueue: string;
  reason: string;
  escalatedBy: OperatorRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type SupportCaseResolved = Readonly<{
  type: "SupportCaseResolved";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  summary: string;
  resolutionCode: string;
  resolvedBy: OperatorRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type SupportCaseClosed = Readonly<{
  type: "SupportCaseClosed";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  reason: CloseReason;
  closedBy: OperatorRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type SupportCaseReopened = Readonly<{
  type: "SupportCaseReopened";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  caseId: SupportCaseId;
  reason: string;
  requesterRef: AccountRef;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type CaseTimelineEntryAppended = Readonly<{
  type: "CaseTimelineEntryAppended";
  eventId: EventId;
  eventType: string;
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  entryId: TimelineEntryId;
  caseId: SupportCaseId;
  eventTypeCode: string;
  visibility: TimelineVisibility;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type CustomerServiceDomainEvent =
  | SupportCaseOpened
  | EvidenceAttached
  | SupportCaseClassified
  | SupportCaseAssigned
  | ManualActionRequested
  | ManualActionResultRecorded
  | SupportCaseEscalated
  | SupportCaseResolved
  | SupportCaseClosed
  | SupportCaseReopened
  | CaseTimelineEntryAppended;

// ─── Boundary Proof ────────────────────────────────────────────────────────────

export type CustomerServiceBoundaryProof = Readonly<{
  journeyOrderMutated: false;
  capacityHoldMutated: false;
  paymentIntentMutated: false;
  entitlementMutated: false;
  postSalesCaseMutated: false;
  crossContextWriteTargets: readonly [];
}>;

const boundaryProof: CustomerServiceBoundaryProof = Object.freeze({
  journeyOrderMutated: false,
  capacityHoldMutated: false,
  paymentIntentMutated: false,
  entitlementMutated: false,
  postSalesCaseMutated: false,
  crossContextWriteTargets: Object.freeze([]) as readonly [],
});

export function customerServiceBoundaryProof(): CustomerServiceBoundaryProof {
  return boundaryProof;
}

// ─── SupportCase Aggregate ─────────────────────────────────────────────────────

export type SupportCaseSnapshot = Readonly<{
  caseId: SupportCaseId;
  status: SupportCaseStatus;
  requesterRef: AccountRef;
  channel: CaseChannel;
  classification?: CaseClassification;
  priority?: CasePriority;
  ownerQueue?: string;
  assignedTo?: OperatorRef;
  description: string;
  businessReferences: BusinessReferences;
  openedAt: Date;
  resolvedAt?: Date;
  resolution?: Resolution;
  escalation?: EscalationInfo;
  closedAt?: Date;
  closeReason?: CloseReason;
  closedBy?: OperatorRef;
  correlationId: CorrelationId;
  causationId?: CausationId;
}>;

export class SupportCase {
  private constructor(private readonly snapshot: SupportCaseSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static open(command: OpenSupportCase): { case: SupportCase; event: SupportCaseOpened } {
    validateOpenCommand(command);

    const snapshot: SupportCaseSnapshot = deepFreeze({
      caseId: command.caseId,
      status: "Opened" as const,
      requesterRef: command.requesterRef,
      channel: command.channel,
      classification: command.classification,
      priority: command.priority,
      description: command.description,
      businessReferences: Object.freeze({ ...(command.businessReferences ?? {}) }) as BusinessReferences,
      openedAt: new Date(command.openedAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
    });

    const supportCase = new SupportCase(snapshot);

    const event: SupportCaseOpened = deepFreeze({
      type: "SupportCaseOpened" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseOpened",
      schemaVersion: 1,
      occurredAt: new Date(command.openedAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
      producer: "customer-service",
      caseId: snapshot.caseId,
      requesterRef: snapshot.requesterRef,
      channel: snapshot.channel,
      classification: snapshot.classification,
      priority: snapshot.priority,
      description: snapshot.description,
      businessReferences: snapshot.businessReferences,
      boundaryProof,
    });

    return { case: supportCase, event };
  }

  classify(command: ClassifySupportCase): { case: SupportCase; event: SupportCaseClassified } {
    if (!this.canClassify()) {
      throw new DomainError(
        "CASE_NOT_CLASSIFIABLE",
        `SupportCase ${this.id} in ${this.status} cannot be classified`,
      );
    }

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Classifying" as const,
      classification: command.classification,
      priority: command.priority,
    });

    const event: SupportCaseClassified = deepFreeze({
      type: "SupportCaseClassified" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseClassified",
      schemaVersion: 1,
      occurredAt: new Date(command.classifiedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      caseId: this.snapshot.caseId,
      classification: command.classification,
      priority: command.priority,
      classifiedBy: command.classifiedBy,
      boundaryProof,
    });

    return { case: new SupportCase(newSnapshot), event };
  }

  assign(command: AssignSupportCase): { case: SupportCase; event: SupportCaseAssigned } {
    if (!this.canAssign()) {
      throw new DomainError(
        "CASE_NOT_ASSIGNABLE",
        `SupportCase ${this.id} in ${this.status} cannot be assigned`,
      );
    }

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Assigned" as const,
      ownerQueue: command.ownerQueue,
      assignedTo: command.assignedTo,
    });

    const event: SupportCaseAssigned = deepFreeze({
      type: "SupportCaseAssigned" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseAssigned",
      schemaVersion: 1,
      occurredAt: new Date(command.assignedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      caseId: this.snapshot.caseId,
      ownerQueue: command.ownerQueue,
      assignedTo: command.assignedTo,
      assignedBy: command.assignedBy,
      boundaryProof,
    });

    return { case: new SupportCase(newSnapshot), event };
  }

  escalate(command: EscalateCase): { case: SupportCase; event: SupportCaseEscalated } {
    if (!this.canEscalate()) {
      throw new DomainError(
        "CASE_NOT_ESCALATABLE",
        `SupportCase ${this.id} in ${this.status} cannot be escalated`,
      );
    }

    const escalation: EscalationInfo = deepFreeze({
      escalationRef: `escl-${crypto.randomUUID()}`,
      targetQueue: command.targetQueue,
      reason: command.reason,
      escalatedAt: new Date(command.escalatedAt),
      escalatedBy: command.escalatedBy,
    });

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Escalated" as const,
      escalation,
    });

    const event: SupportCaseEscalated = deepFreeze({
      type: "SupportCaseEscalated" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseEscalated",
      schemaVersion: 1,
      occurredAt: new Date(command.escalatedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      caseId: this.snapshot.caseId,
      targetQueue: command.targetQueue,
      reason: command.reason,
      escalatedBy: command.escalatedBy,
      boundaryProof,
    });

    return { case: new SupportCase(newSnapshot), event };
  }

  resolve(command: ResolveCase): { case: SupportCase; event: SupportCaseResolved } {
    if (!this.canResolve()) {
      throw new DomainError(
        "CASE_NOT_RESOLVABLE",
        `SupportCase ${this.id} in ${this.status} cannot be resolved`,
      );
    }

    const resolution: Resolution = deepFreeze({
      summary: command.summary,
      resolutionCode: command.resolutionCode,
      resolvedAt: new Date(command.resolvedAt),
      resolvedBy: command.resolvedBy,
    });

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Resolved" as const,
      resolution,
      resolvedAt: new Date(command.resolvedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
    });

    const event: SupportCaseResolved = deepFreeze({
      type: "SupportCaseResolved" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseResolved",
      schemaVersion: 1,
      occurredAt: new Date(command.resolvedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      caseId: this.snapshot.caseId,
      summary: command.summary,
      resolutionCode: command.resolutionCode,
      resolvedBy: command.resolvedBy,
      boundaryProof,
    });

    return { case: new SupportCase(newSnapshot), event };
  }

  close(command: CloseCase): { case: SupportCase; event: SupportCaseClosed } {
    if (!this.canClose()) {
      throw new DomainError(
        "CASE_NOT_CLOSABLE",
        `SupportCase ${this.id} in ${this.status} cannot be closed`,
      );
    }

    // Invariant: closure requires either a resolution record or explicit escalation
    if (command.reason === "RESOLVED" && !this.snapshot.resolution) {
      throw new DomainError(
        "CLOSURE_WITHOUT_RESOLUTION",
        "Cannot close a case as RESOLVED without a resolution record",
      );
    }
    if (command.reason === "ESCALATED" && !this.snapshot.escalation) {
      throw new DomainError(
        "CLOSURE_WITHOUT_ESCALATION",
        "Cannot close a case as ESCALATED without an escalation record",
      );
    }

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Closed" as const,
      closedAt: new Date(command.closedAt),
      closeReason: command.reason,
      closedBy: command.closedBy,
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
    });

    const event: SupportCaseClosed = deepFreeze({
      type: "SupportCaseClosed" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseClosed",
      schemaVersion: 1,
      occurredAt: new Date(command.closedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      caseId: this.snapshot.caseId,
      reason: command.reason,
      closedBy: command.closedBy,
      boundaryProof,
    });

    return { case: new SupportCase(newSnapshot), event };
  }

  reopen(command: ReopenCase): { case: SupportCase; event: SupportCaseReopened } {
    if (this.snapshot.status !== "Closed" && this.snapshot.status !== "Resolved") {
      throw new DomainError(
        "CASE_NOT_REOPENABLE",
        `SupportCase ${this.id} in ${this.status} cannot be reopened`,
      );
    }

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Reopened" as const,
      closedAt: undefined,
      closeReason: undefined,
      closedBy: undefined,
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
    });

    const event: SupportCaseReopened = deepFreeze({
      type: "SupportCaseReopened" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "SupportCaseReopened",
      schemaVersion: 1,
      occurredAt: new Date(command.reopenedAt),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      caseId: this.snapshot.caseId,
      reason: command.reason,
      requesterRef: command.requesterRef,
      boundaryProof,
    });

    return { case: new SupportCase(newSnapshot), event };
  }

  // ─── State machine guards ──────────────────────────────────────────────────

  private canClassify(): boolean {
    return this.snapshot.status === "Opened";
  }

  private canAssign(): boolean {
    return this.snapshot.status === "Classifying" || this.snapshot.status === "Opened";
  }

  private canEscalate(): boolean {
    return (
      this.snapshot.status === "Assigned" ||
      this.snapshot.status === "InProgress" ||
      this.snapshot.status === "WaitingExternal" ||
      this.snapshot.status === "Reopened"
    );
  }

  private canResolve(): boolean {
    return (
      this.snapshot.status === "Assigned" ||
      this.snapshot.status === "InProgress" ||
      this.snapshot.status === "WaitingCustomer" ||
      this.snapshot.status === "WaitingExternal" ||
      this.snapshot.status === "Escalated" ||
      this.snapshot.status === "Reopened"
    );
  }

  private canClose(): boolean {
    return (
      this.snapshot.status === "Resolved" ||
      this.snapshot.status === "Escalated" ||
      this.snapshot.status === "Opened" ||
      this.snapshot.status === "Classifying" ||
      this.snapshot.status === "Reopened"
    );
  }

  // ─── Getters ───────────────────────────────────────────────────────────────

  get id(): SupportCaseId {
    return this.snapshot.caseId;
  }

  get status(): SupportCaseStatus {
    return this.snapshot.status;
  }

  get requesterRef(): AccountRef {
    return this.snapshot.requesterRef;
  }

  get correlationId(): CorrelationId {
    return this.snapshot.correlationId;
  }

  toSnapshot(): SupportCaseSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── EvidenceRef Aggregate ────────────────────────────────────────────────────

export type EvidenceRefSnapshot = Readonly<{
  evidenceId: EvidenceId;
  caseId: SupportCaseId;
  evidenceType: EvidenceType;
  reference: string;
  summary: string;
  accessLevel: EvidenceAccessLevel;
  attachedBy: OperatorRef;
  attachedAt: Date;
}>;

export class EvidenceRef {
  private constructor(private readonly snapshot: EvidenceRefSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static attach(command: AttachEvidence): { evidence: EvidenceRef; event: EvidenceAttached } {
    validateAttachEvidenceCommand(command);

    const snapshot: EvidenceRefSnapshot = deepFreeze({
      evidenceId: command.evidenceId,
      caseId: command.caseId,
      evidenceType: command.evidenceType,
      reference: command.reference,
      summary: command.summary,
      accessLevel: command.accessLevel,
      attachedBy: command.attachedBy,
      attachedAt: new Date(command.attachedAt),
    });

    const evidence = new EvidenceRef(snapshot);

    const event: EvidenceAttached = deepFreeze({
      type: "EvidenceAttached" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "EvidenceAttached",
      schemaVersion: 1,
      occurredAt: new Date(command.attachedAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
      producer: "customer-service",
      evidenceId: snapshot.evidenceId,
      caseId: snapshot.caseId,
      evidenceType: snapshot.evidenceType,
      reference: snapshot.reference,
      summary: snapshot.summary,
      accessLevel: snapshot.accessLevel,
      attachedBy: snapshot.attachedBy,
      boundaryProof,
    });

    return { evidence, event };
  }

  get id(): EvidenceId {
    return this.snapshot.evidenceId;
  }

  get caseId(): SupportCaseId {
    return this.snapshot.caseId;
  }

  toSnapshot(): EvidenceRefSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── ManualActionRequest Aggregate ─────────────────────────────────────────────

export type ManualActionRequestSnapshot = Readonly<{
  manualActionId: ManualActionId;
  caseId: SupportCaseId;
  targetDomain: ManualActionTargetDomain;
  commandType: string;
  operatorRef: OperatorRef;
  reason: string;
  evidenceRefs: readonly EvidenceId[];
  description: string;
  requiresApproval: boolean;
  status: ManualActionStatus;
  approvalRef?: ApprovalRef;
  resultSummary?: string;
  requestedAt: Date;
  outcomeRecordedAt?: Date;
}>;

export class ManualActionRequest {
  private constructor(private readonly snapshot: ManualActionRequestSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static request(command: RequestManualAction): { action: ManualActionRequest; event: ManualActionRequested } {
    validateManualActionRequest(command);

    const snapshot: ManualActionRequestSnapshot = deepFreeze({
      manualActionId: command.manualActionId,
      caseId: command.caseId,
      targetDomain: command.targetDomain,
      commandType: command.commandType,
      operatorRef: command.operatorRef,
      reason: command.reason,
      evidenceRefs: Object.freeze([...(command.evidenceRefs ?? [])]),
      description: command.description,
      requiresApproval: command.requiresApproval,
      status: "Requested" as const,
      requestedAt: new Date(command.requestedAt),
    });

    const action = new ManualActionRequest(snapshot);

    const event: ManualActionRequested = deepFreeze({
      type: "ManualActionRequested" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "ManualActionRequested",
      schemaVersion: 1,
      occurredAt: new Date(command.requestedAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
      producer: "customer-service",
      manualActionId: snapshot.manualActionId,
      caseId: snapshot.caseId,
      targetDomain: snapshot.targetDomain,
      commandType: snapshot.commandType,
      operatorRef: snapshot.operatorRef,
      reason: snapshot.reason,
      evidenceRefs: snapshot.evidenceRefs,
      description: snapshot.description,
      requiresApproval: snapshot.requiresApproval,
      boundaryProof,
    });

    return { action, event };
  }

  recordOutcome(command: RecordActionOutcome): { action: ManualActionRequest; event: ManualActionResultRecorded } {
    if (this.snapshot.status !== "Requested" && this.snapshot.status !== "Approved" && this.snapshot.status !== "Dispatched" && this.snapshot.status !== "WaitingExternal") {
      throw new DomainError(
        "ACTION_NOT_RECORDABLE",
        `ManualActionRequest ${this.id} in ${this.snapshot.status} cannot record outcome`,
      );
    }

    const newSnapshot: ManualActionRequestSnapshot = deepFreeze({
      ...this.snapshot,
      status: command.outcome as ManualActionStatus,
      resultSummary: command.resultSummary,
      approvalRef: command.approvalRef,
      outcomeRecordedAt: new Date(command.recordedAt),
    });

    const event: ManualActionResultRecorded = deepFreeze({
      type: "ManualActionResultRecorded" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "ManualActionResultRecorded",
      schemaVersion: 1,
      occurredAt: new Date(command.recordedAt),
      correlationId: this.snapshot.caseId, // use caseId as correlation for tracing
      causationId: this.snapshot.manualActionId,
      producer: "customer-service",
      manualActionId: this.snapshot.manualActionId,
      caseId: this.snapshot.caseId,
      outcome: command.outcome,
      resultSummary: command.resultSummary,
      approvalRef: command.approvalRef,
      boundaryProof,
    });

    return { action: new ManualActionRequest(newSnapshot), event };
  }

  get id(): ManualActionId {
    return this.snapshot.manualActionId;
  }

  get caseId(): SupportCaseId {
    return this.snapshot.caseId;
  }

  get status(): ManualActionStatus {
    return this.snapshot.status;
  }

  get targetDomain(): ManualActionTargetDomain {
    return this.snapshot.targetDomain;
  }

  toSnapshot(): ManualActionRequestSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── CaseTimeline Aggregate ────────────────────────────────────────────────────

export type TimelineEntrySnapshot = Readonly<{
  entryId: TimelineEntryId;
  caseId: SupportCaseId;
  eventType: string;
  payload: Readonly<Record<string, unknown>>;
  visibility: TimelineVisibility;
  occurredAt: Date;
}>;

export type CaseTimelineSnapshot = Readonly<{
  caseId: SupportCaseId;
  entries: readonly TimelineEntrySnapshot[];
}>;

export class CaseTimeline {
  private constructor(private readonly snapshot: CaseTimelineSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static create(caseId: SupportCaseId): CaseTimeline {
    requireNonBlank(caseId, "caseId");
    const snapshot: CaseTimelineSnapshot = deepFreeze({
      caseId,
      entries: Object.freeze([]),
    });
    return new CaseTimeline(snapshot);
  }

  append(
    command: AppendTimelineEntry,
  ): { timeline: CaseTimeline; event: CaseTimelineEntryAppended } {
    if (command.caseId !== this.snapshot.caseId) {
      throw new DomainError(
        "TIMELINE_CASE_MISMATCH",
        `Timeline entry case ${command.caseId} does not match timeline case ${this.snapshot.caseId}`,
      );
    }

    const entry: TimelineEntrySnapshot = deepFreeze({
      entryId: command.entryId,
      caseId: command.caseId,
      eventType: command.eventType,
      payload: deepFreeze(cloneForSnapshot(command.payload)),
      visibility: command.visibility,
      occurredAt: new Date(command.occurredAt),
    });

    // Append-only: new entries are added to the end
    const entries = [...this.snapshot.entries, entry];

    const newSnapshot: CaseTimelineSnapshot = deepFreeze({
      ...this.snapshot,
      entries: Object.freeze(entries),
    });

    const event: CaseTimelineEntryAppended = deepFreeze({
      type: "CaseTimelineEntryAppended" as const,
      eventId: `evt-${crypto.randomUUID()}`,
      eventType: "CaseTimelineEntryAppended",
      schemaVersion: 1,
      occurredAt: new Date(command.occurredAt),
      correlationId: `corr-${crypto.randomUUID()}`,
      producer: "customer-service",
      entryId: command.entryId,
      caseId: command.caseId,
      eventTypeCode: command.eventType,
      visibility: command.visibility,
      boundaryProof,
    });

    return { timeline: new CaseTimeline(newSnapshot), event };
  }

  get caseId(): SupportCaseId {
    return this.snapshot.caseId;
  }

  get entries(): readonly TimelineEntrySnapshot[] {
    return this.snapshot.entries;
  }

  toSnapshot(): CaseTimelineSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

// ─── Validation helpers ────────────────────────────────────────────────────────

function validateOpenCommand(command: OpenSupportCase): void {
  requireNonBlank(command.caseId, "caseId");
  requireNonBlank(command.requesterRef, "requesterRef");
  requireNonBlank(command.description, "description");
  requireNonBlank(command.correlationId, "correlationId");
  if (!Number.isFinite(command.openedAt.getTime())) {
    throw new DomainError("INVALID_OPENED_AT", "openedAt must be a valid Date");
  }
}

function validateAttachEvidenceCommand(command: AttachEvidence): void {
  requireNonBlank(command.evidenceId, "evidenceId");
  requireNonBlank(command.caseId, "caseId");
  requireNonBlank(command.reference, "reference");
  requireNonBlank(command.summary, "summary");
  requireNonBlank(command.attachedBy, "attachedBy");
  requireNonBlank(command.correlationId, "correlationId");
  if (!Number.isFinite(command.attachedAt.getTime())) {
    throw new DomainError("INVALID_ATTACHED_AT", "attachedAt must be a valid Date");
  }
}

function validateManualActionRequest(command: RequestManualAction): void {
  requireNonBlank(command.manualActionId, "manualActionId");
  requireNonBlank(command.caseId, "caseId");
  requireNonBlank(command.targetDomain, "targetDomain");
  requireNonBlank(command.commandType, "commandType");
  requireNonBlank(command.operatorRef, "operatorRef");
  requireNonBlank(command.reason, "reason");
  requireNonBlank(command.description, "description");
  requireNonBlank(command.correlationId, "correlationId");
  if (!Number.isFinite(command.requestedAt.getTime())) {
    throw new DomainError("INVALID_REQUESTED_AT", "requestedAt must be a valid Date");
  }
}

function requireNonBlank(value: string | undefined, label: string): void {
  if (!value || value.trim().length === 0) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${label} is required`);
  }
}

// ─── Deep immutability helpers ─────────────────────────────────────────────────

function deepFreeze<T>(value: T): T {
  if (value && typeof value === "object") {
    for (const nested of Object.values(value as Record<string, unknown> | unknown[])) {
      deepFreeze(nested);
    }
    Object.freeze(value);
  }
  return value;
}

function cloneForSnapshot<T>(value: T): T {
  if (value instanceof Date) {
    return new Date(value) as T;
  }
  if (Array.isArray(value)) {
    return value.map((entry) => cloneForSnapshot(entry)) as T;
  }
  if (value && typeof value === "object") {
    const clone: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      clone[key] = cloneForSnapshot(nested);
    }
    return clone as T;
  }
  return value;
}
