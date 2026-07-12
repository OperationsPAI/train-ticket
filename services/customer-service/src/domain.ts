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

import { newEventId, uuidV7 } from "@trainticket/ts-kit";

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

export type EscalationLevel =
  | "L1_AGENT"
  | "L2_SPECIALIST"
  | "L3_SUPERVISOR";

export type EscalationTriggerCondition =
  | "L1_UNRESOLVED_30M"
  | "L2_UNRESOLVED_2H"
  | "CUSTOMER_REQUEST"
  | "VIP_CUSTOMER"
  | "RESPONSE_SLA_BREACH"
  | "MANUAL";

export type CustomerTier =
  | "STANDARD"
  | "SILVER"
  | "GOLD"
  | "PLATINUM"
  | "DIAMOND";

export type SlaBreachType = "RESPONSE" | "RESOLUTION";

export type CompensationType = "POINTS" | "VOUCHER" | "CASH" | "UPGRADE";

export type CompensationStatus = "OFFERED" | "ACCEPTED" | "ISSUED" | "REJECTED";

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
  fromLevel?: EscalationLevel;
  toLevel?: EscalationLevel;
  triggerCondition?: EscalationTriggerCondition;
}>;

export type EscalationHistoryEntry = Readonly<{
  escalationRef: EscalationRef;
  fromLevel: EscalationLevel;
  toLevel: EscalationLevel;
  triggerCondition: EscalationTriggerCondition;
  reason: string;
  escalatedAt: Date;
  escalatedBy: OperatorRef;
}>;

export type SlaTrackerSnapshot = Readonly<{
  priority: CasePriority;
  responseTargetMinutes: number;
  resolutionTargetMinutes: number;
  openedAt: Date;
  firstResponseAt?: Date;
  resolvedAt?: Date;
  responseBreachedAt?: Date;
  resolutionBreachedAt?: Date;
}>;

export type SlaBreachRecord = Readonly<{
  ticketId: SupportCaseId;
  breachType: SlaBreachType;
  breachedAt: Date;
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
  customerTier?: CustomerTier;
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
  correlationId: CorrelationId;
  causationId?: CausationId;
  recordedAt: Date;
}>;

export type EscalateCase = Readonly<{
  caseId: SupportCaseId;
  targetQueue: string;
  reason: string;
  escalatedBy: OperatorRef;
  toLevel?: EscalationLevel;
  triggerCondition?: EscalationTriggerCondition;
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

export type OfferCompensation = Readonly<{
  offerId: string;
  ticketId: SupportCaseId;
  type: CompensationType;
  amountMinor: number;
  authorizationLevel: EscalationLevel;
  offeredBy: OperatorRef;
  correlationId: CorrelationId;
  causationId?: CausationId;
  offeredAt: Date;
}>;

export type AcceptCompensation = Readonly<{
  offerId: string;
  acceptedAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
}>;

export type IssueCompensation = Readonly<{
  offerId: string;
  issuedAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
}>;

export type AppendTimelineEntry = Readonly<{
  entryId: TimelineEntryId;
  caseId: SupportCaseId;
  eventType: string;
  payload: Readonly<Record<string, unknown>>;
  visibility: TimelineVisibility;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
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
  fromLevel: EscalationLevel;
  toLevel: EscalationLevel;
  triggerCondition: EscalationTriggerCondition;
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

export type TicketEscalated = Omit<SupportCaseEscalated, "type" | "eventId" | "eventType"> & Readonly<{
  type: "TicketEscalated";
  eventId: EventId;
  eventType: "TicketEscalated";
}>;
export type TicketResolved = Omit<SupportCaseResolved, "type" | "eventId" | "eventType"> & Readonly<{
  type: "TicketResolved";
  eventId: EventId;
  eventType: "TicketResolved";
}>;
export type TicketReopened = Omit<SupportCaseReopened, "type" | "eventId" | "eventType"> & Readonly<{
  type: "TicketReopened";
  eventId: EventId;
  eventType: "TicketReopened";
}>;

export type SlaBreached = Readonly<{
  type: "SlaBreach";
  eventId: EventId;
  eventType: "SlaBreach";
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  ticketId: SupportCaseId;
  breachType: SlaBreachType;
  breachedAt: Date;
  priority: CasePriority;
  targetMinutes: number;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type CompensationOffered = Readonly<{
  type: "CompensationOffered";
  eventId: EventId;
  eventType: "CompensationOffered";
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  offerId: string;
  ticketId: SupportCaseId;
  compensationType: CompensationType;
  amountMinor: number;
  authorizationLevel: EscalationLevel;
  status: CompensationStatus;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type CompensationAccepted = Readonly<{
  type: "CompensationAccepted";
  eventId: EventId;
  eventType: "CompensationAccepted";
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  offerId: string;
  ticketId: SupportCaseId;
  boundaryProof: CustomerServiceBoundaryProof;
}>;

export type CompensationIssued = Readonly<{
  type: "CompensationIssued";
  eventId: EventId;
  eventType: "CompensationIssued";
  schemaVersion: number;
  occurredAt: Date;
  correlationId: CorrelationId;
  causationId?: CausationId;
  producer: string;
  offerId: string;
  ticketId: SupportCaseId;
  compensationType: CompensationType;
  amountMinor: number;
  authorizationLevel: EscalationLevel;
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
  | TicketEscalated
  | TicketResolved
  | TicketReopened
  | SlaBreached
  | CompensationOffered
  | CompensationAccepted
  | CompensationIssued
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

export function toTicketEscalated(event: SupportCaseEscalated): TicketEscalated {
  return deepFreeze({ ...event, type: "TicketEscalated", eventId: newEventId(), eventType: "TicketEscalated" });
}

export function toTicketResolved(event: SupportCaseResolved): TicketResolved {
  return deepFreeze({ ...event, type: "TicketResolved", eventId: newEventId(), eventType: "TicketResolved" });
}

export function toTicketReopened(event: SupportCaseReopened): TicketReopened {
  return deepFreeze({ ...event, type: "TicketReopened", eventId: newEventId(), eventType: "TicketReopened" });
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
  customerTier: CustomerTier;
  escalationLevel: EscalationLevel;
  escalationHistory: readonly EscalationHistoryEntry[];
  slaTracker: SlaTrackerSnapshot;
  slaBreaches: readonly SlaBreachRecord[];
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

  static fromSnapshot(snapshot: SupportCaseSnapshot): SupportCase {
    return new SupportCase(deepFreeze(cloneForSnapshot(snapshot)));
  }

  static open(command: OpenSupportCase): { case: SupportCase; event: SupportCaseOpened } {
    validateOpenCommand(command);

    const snapshot: SupportCaseSnapshot = deepFreeze({
      caseId: command.caseId,
      status: "Opened" as const,
      requesterRef: command.requesterRef,
      channel: command.channel,
      classification: command.classification,
      priority: command.priority ?? "NORMAL",
      description: command.description,
      businessReferences: Object.freeze({ ...(command.businessReferences ?? {}) }) as BusinessReferences,
      openedAt: new Date(command.openedAt),
      customerTier: command.customerTier ?? "STANDARD",
      escalationLevel: initialEscalationLevel(command.customerTier),
      escalationHistory: Object.freeze([]),
      slaTracker: SlaTracker.start(command.priority ?? "NORMAL", command.openedAt).toSnapshot(),
      slaBreaches: Object.freeze([]),
      correlationId: command.correlationId,
      causationId: command.causationId,
    });

    const supportCase = new SupportCase(snapshot);

    const event: SupportCaseOpened = deepFreeze({
      type: "SupportCaseOpened" as const,
      eventId: newEventId(),
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
      slaTracker: SlaTracker.fromSnapshot(this.snapshot.slaTracker).withPriority(command.priority).toSnapshot(),
    });

    const event: SupportCaseClassified = deepFreeze({
      type: "SupportCaseClassified" as const,
      eventId: newEventId(),
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
      slaTracker: SlaTracker.fromSnapshot(this.snapshot.slaTracker).recordFirstResponse(command.assignedAt).toSnapshot(),
    });

    const event: SupportCaseAssigned = deepFreeze({
      type: "SupportCaseAssigned" as const,
      eventId: newEventId(),
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

    const fromLevel = this.snapshot.escalationLevel;
    const toLevel = command.toLevel ?? nextEscalationLevel(fromLevel);
    if (levelRank(toLevel) < levelRank(fromLevel)) {
      throw new DomainError("INVALID_ESCALATION_LEVEL", "Escalation cannot move to a lower level");
    }
    if (toLevel === fromLevel && command.triggerCondition !== "MANUAL") {
      throw new DomainError("ALREADY_AT_ESCALATION_LEVEL", `SupportCase ${this.id} is already at ${fromLevel}`);
    }
    const triggerCondition = command.triggerCondition ?? "MANUAL";

    const escalation: EscalationInfo = deepFreeze({
      escalationRef: `escl-${uuidV7()}`,
      targetQueue: command.targetQueue,
      reason: command.reason,
      escalatedAt: new Date(command.escalatedAt),
      escalatedBy: command.escalatedBy,
      fromLevel,
      toLevel,
      triggerCondition,
    });
    const historyEntry: EscalationHistoryEntry = deepFreeze({
      escalationRef: escalation.escalationRef,
      fromLevel,
      toLevel,
      triggerCondition,
      reason: command.reason,
      escalatedAt: new Date(command.escalatedAt),
      escalatedBy: command.escalatedBy,
    });

    const newSnapshot: SupportCaseSnapshot = deepFreeze({
      ...this.snapshot,
      status: "Escalated" as const,
      escalationLevel: toLevel,
      escalation,
      escalationHistory: Object.freeze([...this.snapshot.escalationHistory, historyEntry]),
    });

    const event: SupportCaseEscalated = deepFreeze({
      type: "SupportCaseEscalated" as const,
      eventId: newEventId(),
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
      fromLevel,
      toLevel,
      triggerCondition,
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
      slaTracker: SlaTracker.fromSnapshot(this.snapshot.slaTracker).recordResolution(command.resolvedAt).toSnapshot(),
      correlationId: command.correlationId ?? this.snapshot.correlationId,
      causationId: command.causationId ?? this.snapshot.causationId,
    });

    const event: SupportCaseResolved = deepFreeze({
      type: "SupportCaseResolved" as const,
      eventId: newEventId(),
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

  markSlaBreach(breachType: SlaBreachType, breachedAt: Date): SupportCase {
    const alreadyRecorded = this.snapshot.slaBreaches.some((breach) => breach.breachType === breachType);
    if (alreadyRecorded) {
      return this;
    }
    const breach: SlaBreachRecord = deepFreeze({ ticketId: this.id, breachType, breachedAt: new Date(breachedAt) });
    return new SupportCase(deepFreeze({
      ...this.snapshot,
      slaTracker: SlaTracker.fromSnapshot(this.snapshot.slaTracker).markBreached(breachType, breachedAt).toSnapshot(),
      slaBreaches: Object.freeze([...this.snapshot.slaBreaches, breach]),
    }));
  }

  createSlaBreachEvent(breachType: SlaBreachType, breachedAt: Date, correlationId?: CorrelationId, causationId?: CausationId): SlaBreached {
    const targetMinutes = breachType === "RESPONSE" ? this.snapshot.slaTracker.responseTargetMinutes : this.snapshot.slaTracker.resolutionTargetMinutes;
    return deepFreeze({
      type: "SlaBreach",
      eventId: newEventId(),
      eventType: "SlaBreach",
      schemaVersion: 1,
      occurredAt: new Date(breachedAt),
      correlationId: correlationId ?? this.snapshot.correlationId,
      causationId: causationId ?? this.snapshot.causationId,
      producer: "customer-service",
      ticketId: this.id,
      breachType,
      breachedAt: new Date(breachedAt),
      priority: this.snapshot.slaTracker.priority,
      targetMinutes,
      boundaryProof,
    });
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
      eventId: newEventId(),
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
      eventId: newEventId(),
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
      this.snapshot.status === "Opened" ||
      this.snapshot.status === "Classifying" ||
      this.snapshot.status === "Assigned" ||
      this.snapshot.status === "InProgress" ||
      this.snapshot.status === "WaitingExternal" ||
      this.snapshot.status === "Reopened" ||
      this.snapshot.status === "Escalated"
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

  get escalationLevel(): EscalationLevel {
    return this.snapshot.escalationLevel;
  }

  get slaTracker(): SlaTracker {
    return SlaTracker.fromSnapshot(this.snapshot.slaTracker);
  }

  get correlationId(): CorrelationId {
    return this.snapshot.correlationId;
  }

  toSnapshot(): SupportCaseSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

export type EscalationRule = Readonly<{
  fromLevel: EscalationLevel;
  triggerCondition: EscalationTriggerCondition;
  toLevel: EscalationLevel;
}>;

export class EscalationPolicy {
  static readonly rules: readonly EscalationRule[] = Object.freeze([
    { fromLevel: "L1_AGENT", triggerCondition: "L1_UNRESOLVED_30M", toLevel: "L2_SPECIALIST" },
    { fromLevel: "L2_SPECIALIST", triggerCondition: "L2_UNRESOLVED_2H", toLevel: "L3_SUPERVISOR" },
    { fromLevel: "L1_AGENT", triggerCondition: "CUSTOMER_REQUEST", toLevel: "L2_SPECIALIST" },
    { fromLevel: "L2_SPECIALIST", triggerCondition: "CUSTOMER_REQUEST", toLevel: "L3_SUPERVISOR" },
    { fromLevel: "L1_AGENT", triggerCondition: "RESPONSE_SLA_BREACH", toLevel: "L2_SPECIALIST" },
    { fromLevel: "L2_SPECIALIST", triggerCondition: "RESPONSE_SLA_BREACH", toLevel: "L3_SUPERVISOR" },
  ]);

  static autoEscalation(now: Date, snapshot: SupportCaseSnapshot): EscalationRule | undefined {
    if (isTerminalCaseStatus(snapshot.status)) {
      return undefined;
    }
    const elapsedMinutes = minutesBetween(snapshot.openedAt, now);
    if (snapshot.escalationLevel === "L1_AGENT" && elapsedMinutes > 30) {
      return this.rules[0];
    }
    if (snapshot.escalationLevel === "L2_SPECIALIST") {
      const levelStartedAt = snapshot.escalationHistory.at(-1)?.escalatedAt ?? snapshot.openedAt;
      if (minutesBetween(levelStartedAt, now) > 120) {
        return this.rules[1];
      }
    }
    return undefined;
  }

  static targetFor(triggerCondition: EscalationTriggerCondition, fromLevel: EscalationLevel): EscalationLevel {
    return this.rules.find((rule) => rule.fromLevel === fromLevel && rule.triggerCondition === triggerCondition)?.toLevel ?? nextEscalationLevel(fromLevel);
  }
}

export class SlaPolicy {
  private constructor(
    public readonly priority: CasePriority,
    public readonly responseTargetMinutes: number,
    public readonly resolutionTargetMinutes: number,
  ) {}

  static forPriority(priority: CasePriority): SlaPolicy {
    switch (priority) {
      case "URGENT": return new SlaPolicy(priority, 5, 30);
      case "HIGH": return new SlaPolicy(priority, 15, 120);
      case "NORMAL": return new SlaPolicy(priority, 60, 1440);
      case "LOW": return new SlaPolicy(priority, 1440, 4320);
    }
  }
}

export class SlaTracker {
  private constructor(private readonly snapshot: SlaTrackerSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static start(priority: CasePriority, openedAt: Date): SlaTracker {
    const policy = SlaPolicy.forPriority(priority);
    return new SlaTracker(deepFreeze({
      priority,
      responseTargetMinutes: policy.responseTargetMinutes,
      resolutionTargetMinutes: policy.resolutionTargetMinutes,
      openedAt: new Date(openedAt),
    }));
  }

  static fromSnapshot(snapshot: SlaTrackerSnapshot): SlaTracker {
    return new SlaTracker(deepFreeze(cloneForSnapshot(snapshot)));
  }

  withPriority(priority: CasePriority): SlaTracker {
    if (priority === this.snapshot.priority) {
      return this;
    }
    const policy = SlaPolicy.forPriority(priority);
    return new SlaTracker(deepFreeze({
      ...this.snapshot,
      priority,
      responseTargetMinutes: policy.responseTargetMinutes,
      resolutionTargetMinutes: policy.resolutionTargetMinutes,
    }));
  }

  recordFirstResponse(firstResponseAt: Date): SlaTracker {
    if (this.snapshot.firstResponseAt) {
      return this;
    }
    return new SlaTracker(deepFreeze({ ...this.snapshot, firstResponseAt: new Date(firstResponseAt) }));
  }

  recordResolution(resolvedAt: Date): SlaTracker {
    return new SlaTracker(deepFreeze({ ...this.snapshot, resolvedAt: new Date(resolvedAt) }));
  }

  breachesAt(now: Date): readonly SlaBreachType[] {
    const breaches: SlaBreachType[] = [];
    if (!this.snapshot.firstResponseAt && !this.snapshot.responseBreachedAt && minutesBetween(this.snapshot.openedAt, now) > this.snapshot.responseTargetMinutes) {
      breaches.push("RESPONSE");
    }
    if (!this.snapshot.resolvedAt && !this.snapshot.resolutionBreachedAt && minutesBetween(this.snapshot.openedAt, now) > this.snapshot.resolutionTargetMinutes) {
      breaches.push("RESOLUTION");
    }
    return Object.freeze(breaches);
  }

  markBreached(breachType: SlaBreachType, breachedAt: Date): SlaTracker {
    return new SlaTracker(deepFreeze({
      ...this.snapshot,
      responseBreachedAt: breachType === "RESPONSE" ? new Date(breachedAt) : this.snapshot.responseBreachedAt,
      resolutionBreachedAt: breachType === "RESOLUTION" ? new Date(breachedAt) : this.snapshot.resolutionBreachedAt,
    }));
  }

  timeToFirstResponseMinutes(): number | undefined {
    return this.snapshot.firstResponseAt ? minutesBetween(this.snapshot.openedAt, this.snapshot.firstResponseAt) : undefined;
  }

  timeToResolutionMinutes(): number | undefined {
    return this.snapshot.resolvedAt ? minutesBetween(this.snapshot.openedAt, this.snapshot.resolvedAt) : undefined;
  }

  isCompliant(): boolean {
    return !this.snapshot.responseBreachedAt && !this.snapshot.resolutionBreachedAt;
  }

  toSnapshot(): SlaTrackerSnapshot {
    return deepFreeze(cloneForSnapshot(this.snapshot));
  }
}

export type CompensationOfferSnapshot = Readonly<{
  offerId: string;
  ticketId: SupportCaseId;
  type: CompensationType;
  amountMinor: number;
  authorizationLevel: EscalationLevel;
  status: CompensationStatus;
  offeredBy: OperatorRef;
  offeredAt: Date;
  acceptedAt?: Date;
  issuedAt?: Date;
}>;

export class CompensationAuthorizer {
  static canAuthorize(level: EscalationLevel, amountMinor: number): boolean {
    if (!Number.isInteger(amountMinor) || amountMinor < 0) {
      return false;
    }
    return amountMinor <= this.limitMinor(level);
  }

  static limitMinor(level: EscalationLevel): number {
    switch (level) {
      case "L1_AGENT": return 5_000;
      case "L2_SPECIALIST": return 20_000;
      case "L3_SUPERVISOR": return 100_000;
    }
  }
}

export class CompensationOffer {
  private constructor(private readonly snapshot: CompensationOfferSnapshot) {
    deepFreeze(this.snapshot);
    Object.freeze(this);
  }

  static fromSnapshot(snapshot: CompensationOfferSnapshot): CompensationOffer {
    return new CompensationOffer(deepFreeze(cloneForSnapshot(snapshot)));
  }

  static offer(command: OfferCompensation): { offer: CompensationOffer; event: CompensationOffered } {
    requireNonBlank(command.offerId, "offerId");
    requireNonBlank(command.ticketId, "ticketId");
    requireNonBlank(command.offeredBy, "offeredBy");
    requireNonBlank(command.correlationId, "correlationId");
    if (!CompensationAuthorizer.canAuthorize(command.authorizationLevel, command.amountMinor)) {
      throw new DomainError("COMPENSATION_AUTHORIZATION_EXCEEDED", `${command.authorizationLevel} cannot authorize ${command.amountMinor} minor units`);
    }
    const snapshot: CompensationOfferSnapshot = deepFreeze({
      offerId: command.offerId,
      ticketId: command.ticketId,
      type: command.type,
      amountMinor: command.amountMinor,
      authorizationLevel: command.authorizationLevel,
      status: "OFFERED",
      offeredBy: command.offeredBy,
      offeredAt: new Date(command.offeredAt),
    });
    const event: CompensationOffered = deepFreeze({
      type: "CompensationOffered", eventId: newEventId(), eventType: "CompensationOffered", schemaVersion: 1,
      occurredAt: new Date(command.offeredAt), correlationId: command.correlationId, causationId: command.causationId, producer: "customer-service",
      offerId: command.offerId, ticketId: command.ticketId, compensationType: command.type, amountMinor: command.amountMinor, authorizationLevel: command.authorizationLevel, status: "OFFERED", boundaryProof,
    });
    return { offer: new CompensationOffer(snapshot), event };
  }

  accept(command: AcceptCompensation): { offer: CompensationOffer; event: CompensationAccepted } {
    if (command.offerId !== this.id) {
      throw new DomainError("COMPENSATION_OFFER_MISMATCH", `Command offerId ${command.offerId} does not match compensation ${this.id}`);
    }
    if (this.snapshot.status !== "OFFERED") {
      throw new DomainError("COMPENSATION_NOT_ACCEPTABLE", `Compensation ${this.id} in ${this.snapshot.status} cannot be accepted`);
    }
    const snapshot = deepFreeze({ ...this.snapshot, status: "ACCEPTED" as const, acceptedAt: new Date(command.acceptedAt) });
    const event: CompensationAccepted = deepFreeze({
      type: "CompensationAccepted", eventId: newEventId(), eventType: "CompensationAccepted", schemaVersion: 1,
      occurredAt: new Date(command.acceptedAt), correlationId: command.correlationId, causationId: command.causationId, producer: "customer-service", offerId: this.id, ticketId: this.snapshot.ticketId, boundaryProof,
    });
    return { offer: new CompensationOffer(snapshot), event };
  }

  issue(command: IssueCompensation): { offer: CompensationOffer; event: CompensationIssued } {
    if (command.offerId !== this.id) {
      throw new DomainError("COMPENSATION_OFFER_MISMATCH", `Command offerId ${command.offerId} does not match compensation ${this.id}`);
    }
    if (this.snapshot.status !== "ACCEPTED") {
      throw new DomainError("COMPENSATION_NOT_ISSUABLE", `Compensation ${this.id} in ${this.snapshot.status} cannot be issued`);
    }
    const snapshot = deepFreeze({ ...this.snapshot, status: "ISSUED" as const, issuedAt: new Date(command.issuedAt) });
    const event: CompensationIssued = deepFreeze({
      type: "CompensationIssued", eventId: newEventId(), eventType: "CompensationIssued", schemaVersion: 1,
      occurredAt: new Date(command.issuedAt), correlationId: command.correlationId, causationId: command.causationId, producer: "customer-service", offerId: this.id, ticketId: this.snapshot.ticketId, compensationType: this.snapshot.type, amountMinor: this.snapshot.amountMinor, authorizationLevel: this.snapshot.authorizationLevel, boundaryProof,
    });
    return { offer: new CompensationOffer(snapshot), event };
  }

  get id(): string { return this.snapshot.offerId; }
  get ticketId(): string { return this.snapshot.ticketId; }
  toSnapshot(): CompensationOfferSnapshot { return deepFreeze(cloneForSnapshot(this.snapshot)); }
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

  static fromSnapshot(snapshot: EvidenceRefSnapshot): EvidenceRef {
    return new EvidenceRef(deepFreeze(cloneForSnapshot(snapshot)));
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
      eventId: newEventId(),
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

  static fromSnapshot(snapshot: ManualActionRequestSnapshot): ManualActionRequest {
    return new ManualActionRequest(deepFreeze(cloneForSnapshot(snapshot)));
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
      eventId: newEventId(),
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
      eventId: newEventId(),
      eventType: "ManualActionResultRecorded",
      schemaVersion: 1,
      occurredAt: new Date(command.recordedAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
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

  static fromSnapshot(snapshot: CaseTimelineSnapshot): CaseTimeline {
    return new CaseTimeline(deepFreeze(cloneForSnapshot(snapshot)));
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
      eventId: newEventId(),
      eventType: "CaseTimelineEntryAppended",
      schemaVersion: 1,
      occurredAt: new Date(command.occurredAt),
      correlationId: command.correlationId,
      causationId: command.causationId,
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

function initialEscalationLevel(customerTier: CustomerTier | undefined): EscalationLevel {
  return isVipTier(customerTier) ? "L2_SPECIALIST" : "L1_AGENT";
}

function isVipTier(customerTier: CustomerTier | undefined): boolean {
  return customerTier === "GOLD" || customerTier === "PLATINUM" || customerTier === "DIAMOND";
}

function nextEscalationLevel(level: EscalationLevel): EscalationLevel {
  switch (level) {
    case "L1_AGENT": return "L2_SPECIALIST";
    case "L2_SPECIALIST": return "L3_SUPERVISOR";
    case "L3_SUPERVISOR": return "L3_SUPERVISOR";
  }
}

function levelRank(level: EscalationLevel): number {
  switch (level) {
    case "L1_AGENT": return 1;
    case "L2_SPECIALIST": return 2;
    case "L3_SUPERVISOR": return 3;
  }
}

function minutesBetween(start: Date, end: Date): number {
  return Math.floor((end.getTime() - start.getTime()) / 60_000);
}

function isTerminalCaseStatus(status: SupportCaseStatus): boolean {
  return status === "Resolved" || status === "Closed";
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
