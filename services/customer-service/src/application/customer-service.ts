import {
  DomainError,
  CaseTimeline,
  EvidenceRef,
  ManualActionRequest,
  SupportCase,
  type CaseChannel,
  type CasePriority,
  type CloseReason,
  type EvidenceAccessLevel,
  type EvidenceType,
  type ManualActionTargetDomain,
  type SupportCaseSnapshot,
  type TimelineEntrySnapshot,
} from "../domain.js";
import { newCommandId, toEventEnvelope, type EventEnvelope, type EventPublisher } from "./messaging.js";
import { uuidV7 } from "@trainticket/ts-kit";

export type SupportCaseDetails = SupportCaseSnapshot &
  Readonly<{
    evidence: readonly ReturnType<EvidenceRef["toSnapshot"]>[];
    timeline: readonly TimelineEntrySnapshot[];
  }>;

export type OpenSupportCaseRequest = Readonly<{
  requesterRef: string;
  channel: CaseChannel;
  classification?: string;
  priority?: CasePriority;
  description: string;
  businessReferences?: Record<string, string>;
}>;

export type AttachEvidenceRequest = Readonly<{
  evidenceType: EvidenceType;
  reference: string;
  summary: string;
  accessLevel: EvidenceAccessLevel;
  attachedBy: string;
}>;

export type ClassifySupportCaseRequest = Readonly<{ classification: string; priority: CasePriority }>;
export type AssignSupportCaseRequest = Readonly<{ assignedTo?: string; ownerQueue: string }>;
export type EscalateCaseRequest = Readonly<{ targetQueue: string; reason: string }>;
export type ResolveCaseRequest = Readonly<{ summary: string; resolutionCode: string }>;
export type CloseCaseRequest = Readonly<{ reason: CloseReason }>;
export type ReopenCaseRequest = Readonly<{ reason: string; requesterRef: string }>;
export type RequestManualActionRequest = Readonly<{
  targetDomain: ManualActionTargetDomain;
  commandType: string;
  operatorRef: string;
  reason: string;
  evidenceRefs?: readonly string[];
  description: string;
  requiresApproval: boolean;
}>;

export class NotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotFoundError";
  }
}

export class CustomerServiceApplication {
  private readonly cases = new Map<string, SupportCase>();
  private readonly evidenceByCase = new Map<string, EvidenceRef[]>();
  private readonly timelines = new Map<string, CaseTimeline>();
  private readonly manualActions = new Map<string, ManualActionRequest>();
  private readonly consumedIntegrationEvents = new Map<string, Readonly<{ source: string; eventType: string; consumedAt: Date; payload: Readonly<Record<string, unknown>> }>>();

  constructor(private readonly publisher: EventPublisher) {}

  async openSupportCase(request: OpenSupportCaseRequest, correlationId: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const openedAt = new Date();
    const { case: supportCase, event } = SupportCase.open({
      caseId: newSupportCaseId(),
      requesterRef: request.requesterRef,
      channel: request.channel,
      classification: request.classification,
      priority: request.priority ?? "NORMAL",
      description: request.description,
      businessReferences: request.businessReferences,
      correlationId,
      causationId,
      openedAt,
    });
    this.cases.set(supportCase.id, supportCase);
    this.timelines.set(supportCase.id, CaseTimeline.create(supportCase.id));
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return supportCase.toSnapshot();
  }

  getSupportCase(caseId: string): SupportCaseDetails {
    const supportCase = this.requireCase(caseId);
    return {
      ...supportCase.toSnapshot(),
      evidence: Object.freeze((this.evidenceByCase.get(caseId) ?? []).map((evidence) => evidence.toSnapshot())),
      timeline: Object.freeze((this.timelines.get(caseId) ?? CaseTimeline.create(caseId)).entries.map((entry) => ({ ...entry }))),
    };
  }

  async attachEvidence(caseId: string, request: AttachEvidenceRequest, correlationId: string, causationId = newCommandId()) {
    this.requireCase(caseId);
    const { evidence, event } = EvidenceRef.attach({
      evidenceId: newEvidenceId(),
      caseId,
      evidenceType: request.evidenceType,
      reference: request.reference,
      summary: request.summary,
      accessLevel: request.accessLevel,
      attachedBy: request.attachedBy,
      correlationId,
      causationId,
      attachedAt: new Date(),
    });
    const evidenceRefs = this.evidenceByCase.get(caseId) ?? [];
    evidenceRefs.push(evidence);
    this.evidenceByCase.set(caseId, evidenceRefs);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return evidence.toSnapshot();
  }

  async classifySupportCase(caseId: string, request: ClassifySupportCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.classify({
      caseId,
      classification: request.classification,
      priority: request.priority,
      classifiedBy: operatorRef,
      correlationId,
      causationId,
      classifiedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async assignSupportCase(caseId: string, request: AssignSupportCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.assign({
      caseId,
      ownerQueue: request.ownerQueue,
      assignedTo: request.assignedTo,
      assignedBy: operatorRef,
      correlationId,
      causationId,
      assignedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async escalateCase(caseId: string, request: EscalateCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.escalate({
      caseId,
      targetQueue: request.targetQueue,
      reason: request.reason,
      escalatedBy: operatorRef,
      correlationId,
      causationId,
      escalatedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async resolveCase(caseId: string, request: ResolveCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.resolve({
      caseId,
      summary: request.summary,
      resolutionCode: request.resolutionCode,
      resolvedBy: operatorRef,
      correlationId,
      causationId,
      resolvedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async closeCase(caseId: string, request: CloseCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.close({
      caseId,
      reason: request.reason,
      closedBy: operatorRef,
      correlationId,
      causationId,
      closedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async reopenCase(caseId: string, request: ReopenCaseRequest, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.reopen({
      caseId,
      reason: request.reason,
      requesterRef: request.requesterRef,
      correlationId,
      causationId,
      reopenedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async requestManualAction(caseId: string, request: RequestManualActionRequest, correlationId: string, causationId = newCommandId()) {
    this.requireCase(caseId);
    const { action, event } = ManualActionRequest.request({
      manualActionId: newManualActionId(),
      caseId,
      targetDomain: request.targetDomain,
      commandType: request.commandType,
      operatorRef: request.operatorRef,
      reason: request.reason,
      evidenceRefs: request.evidenceRefs,
      description: request.description,
      requiresApproval: request.requiresApproval,
      correlationId,
      causationId,
      requestedAt: new Date(),
    });
    this.manualActions.set(action.id, action);
    await this.appendTimelineEntry(caseId, "ManualActionRequested", toEventEnvelope(event, causationId).payload, "INTERNAL_ONLY", event.occurredAt, event.correlationId, event.causationId);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return action.toSnapshot();
  }

  async handleIntegrationEvent(envelope: EventEnvelope): Promise<void> {
    if (this.consumedIntegrationEvents.has(envelope.eventId)) {
      return;
    }

    if (envelope.producer === "admin-audit" && (envelope.eventType === "ManualActionExecuted" || envelope.eventType === "ManualActionRejected")) {
      await this.recordAdminAuditManualActionOutcome(envelope);
    } else {
      for (const supportCase of this.cases.values()) {
        if (caseReferencesEnvelope(supportCase.toSnapshot(), envelope)) {
          await this.appendTimelineEntry(
            supportCase.id,
            envelope.eventType,
            { sourceEventId: envelope.eventId, producer: envelope.producer, payload: envelope.payload },
            "INTERNAL_ONLY",
            new Date(envelope.occurredAt),
            envelope.correlationId,
            envelope.causationId,
          );
        }
      }
    }

    this.consumedIntegrationEvents.set(envelope.eventId, Object.freeze({
      source: envelope.producer,
      eventType: envelope.eventType,
      consumedAt: new Date(),
      payload: envelope.payload,
    }));
  }

  consumedIntegrationEventCount(): number {
    return this.consumedIntegrationEvents.size;
  }

  private async recordAdminAuditManualActionOutcome(envelope: EventEnvelope): Promise<void> {
    const manualActionId = requiredPayloadString(envelope.payload, "manualActionId");
    const action = this.manualActions.get(manualActionId);
    if (!action) {
      return;
    }
    const resultSummary = envelope.eventType === "ManualActionRejected"
      ? requiredPayloadString(envelope.payload, "reason")
      : requiredPayloadString(envelope.payload, "resultSummary");
    const outcome = manualActionOutcome(envelope.eventType, resultSummary);
    const { action: updated, event } = action.recordOutcome({
      manualActionId,
      outcome,
      resultSummary,
      recordedAt: new Date(envelope.occurredAt),
      correlationId: envelope.correlationId,
      causationId: envelope.eventId,
    });
    this.manualActions.set(manualActionId, updated);
    const eventEnvelope = toEventEnvelope(event, envelope.eventId);
    await this.publisher.publish(eventEnvelope);
    await this.appendTimelineEntry(
      updated.caseId,
      "ManualActionResultRecorded",
      eventEnvelope.payload,
      "INTERNAL_ONLY",
      event.occurredAt,
      envelope.correlationId,
      envelope.eventId,
    );
  }

  private async appendTimelineEntry(caseId: string, eventType: string, payload: Readonly<Record<string, unknown>>, visibility: "CUSTOMER_VISIBLE" | "INTERNAL_ONLY", occurredAt: Date, correlationId: string, causationId?: string): Promise<void> {
    const current = this.timelines.get(caseId) ?? CaseTimeline.create(caseId);
    const { timeline, event } = current.append({
      entryId: newTimelineEntryId(),
      caseId,
      eventType,
      payload,
      visibility,
      occurredAt,
      correlationId,
      causationId,
    });
    this.timelines.set(caseId, timeline);
    await this.publisher.publish(toEventEnvelope(event, event.causationId ?? newCommandId()));
  }

  private requireCase(caseId: string): SupportCase {
    const supportCase = this.cases.get(caseId);
    if (!supportCase) {
      throw new NotFoundError(`Support case ${caseId} was not found`);
    }
    return supportCase;
  }
}

export function isDomainError(error: unknown): error is DomainError {
  return error instanceof DomainError;
}

function requiredPayloadString(payload: Record<string, unknown>, field: string): string {
  const value = stringField(payload, field);
  if (value === undefined) {
    throw new DomainError("MISSING_REQUIRED_FIELD", `${field} is required`);
  }
  return value;
}

function manualActionOutcome(eventType: string, resultSummary: string): "Succeeded" | "Failed" | "Rejected" {
  if (eventType === "ManualActionRejected") {
    return "Rejected";
  }
  return resultSummary.trim().toUpperCase().startsWith("FAILED:") ? "Failed" : "Succeeded";
}

function caseReferencesEnvelope(snapshot: SupportCaseSnapshot, envelope: EventEnvelope): boolean {
  if (envelope.producer !== "journey-order" && envelope.producer !== "post-sales") {
    return false;
  }
  const references = snapshot.businessReferences;
  const orderId = stringField(envelope.payload, "orderId") ?? stringField(envelope.payload, "journeyOrderId");
  const postSalesCaseId = stringField(envelope.payload, "caseId");
  return (references.journeyOrderId !== undefined && references.journeyOrderId === orderId)
    || (references.postSalesCaseId !== undefined && references.postSalesCaseId === postSalesCaseId);
}

function stringField(payload: Record<string, unknown>, field: string): string | undefined {
  const value = payload[field];
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function newSupportCaseId(): string {
  return `sc-${uuidV7()}`;
}

function newEvidenceId(): string {
  return `evid-${uuidV7()}`;
}

function newManualActionId(): string {
  return `ma-${uuidV7()}`;
}

function newTimelineEntryId(): string {
  return `tl-${uuidV7()}`;
}
