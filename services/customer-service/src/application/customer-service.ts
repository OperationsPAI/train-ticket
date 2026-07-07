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
import { OptimisticConcurrencyConflict, uuidV7 } from "@trainticket/ts-kit";
import { type CustomerServiceRepository } from "./ports/customer-service-repository.js";

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

  constructor(
    private readonly publisher: EventPublisher,
    private readonly repository?: CustomerServiceRepository,
  ) {}

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
    try {
      await this.repository?.saveNewCase(supportCase.toSnapshot());
    } catch (error) {
      if ((error instanceof OptimisticConcurrencyConflict || isUniqueViolation(error)) && this.repository) {
        const duplicate = await this.repository.findDuplicateOpenCase(supportCase.toSnapshot());
        if (duplicate) {
          return duplicate;
        }
      }
      throw error;
    }
    const timeline = CaseTimeline.create(supportCase.id);
    await this.repository?.saveNewTimeline(timeline.toSnapshot());
    this.cases.set(supportCase.id, supportCase);
    this.timelines.set(supportCase.id, timeline);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return supportCase.toSnapshot();
  }

  getSupportCase(caseId: string): SupportCaseDetails {
    const supportCase = this.requireCaseSync(caseId);
    return {
      ...supportCase.toSnapshot(),
      evidence: Object.freeze((this.evidenceByCase.get(caseId) ?? []).map((evidence) => evidence.toSnapshot())),
      timeline: Object.freeze((this.timelines.get(caseId) ?? CaseTimeline.create(caseId)).entries.map((entry) => ({ ...entry }))),
    };
  }


  async getSupportCaseDetails(caseId: string): Promise<SupportCaseDetails> {
    if (!this.repository) {
      return this.getSupportCase(caseId);
    }
    const supportCase = await this.requireCase(caseId);
    return {
      ...supportCase.toSnapshot(),
      evidence: Object.freeze(await this.repository.evidenceForCase(caseId)),
      timeline: Object.freeze(((await this.findTimeline(caseId)) ?? CaseTimeline.create(caseId)).entries.map((entry) => ({ ...entry }))),
    };
  }

  async attachEvidence(caseId: string, request: AttachEvidenceRequest, correlationId: string, causationId = newCommandId()) {
    await this.requireCase(caseId);
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
    await this.repository?.saveEvidence(evidence.toSnapshot());
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return evidence.toSnapshot();
  }

  async classifySupportCase(caseId: string, request: ClassifySupportCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
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
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async assignSupportCase(caseId: string, request: AssignSupportCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
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
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async escalateCase(caseId: string, request: EscalateCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
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
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async resolveCase(caseId: string, request: ResolveCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
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
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async closeCase(caseId: string, request: CloseCaseRequest, operatorRef: string, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.close({
      caseId,
      reason: request.reason,
      closedBy: operatorRef,
      correlationId,
      causationId,
      closedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async reopenCase(caseId: string, request: ReopenCaseRequest, correlationId?: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const { aggregate: current, version } = await this.requireVersionedCase(caseId);
    const { case: updated, event } = current.reopen({
      caseId,
      reason: request.reason,
      requesterRef: request.requesterRef,
      correlationId,
      causationId,
      reopenedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    if (this.repository) {
      await this.repository.saveCase(updated.toSnapshot(), version ?? 0n);
    }
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async requestManualAction(caseId: string, request: RequestManualActionRequest, correlationId: string, causationId = newCommandId()) {
    await this.requireCase(caseId);
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
    await this.repository?.saveNewManualAction(action.toSnapshot());
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
      for (const supportCase of await this.findCasesReferencingEnvelope(envelope)) {
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
    const versionedAction = await this.findManualAction(manualActionId);
    const action = versionedAction?.aggregate;
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
    if (this.repository && versionedAction) {
      await this.repository.saveManualAction(updated.toSnapshot(), versionedAction.version ?? 0n);
    }
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
    const versionedTimeline = await this.findVersionedTimeline(caseId);
    const current = versionedTimeline?.aggregate ?? CaseTimeline.create(caseId);
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
    if (this.repository) {
      if (versionedTimeline) {
        await this.repository.saveTimeline(timeline.toSnapshot(), versionedTimeline.version);
      } else {
        await this.repository.saveNewTimeline(timeline.toSnapshot());
      }
    }
    await this.publisher.publish(toEventEnvelope(event, event.causationId ?? newCommandId()));
  }

  private requireCaseSync(caseId: string): SupportCase {
    const supportCase = this.cases.get(caseId);
    if (!supportCase) {
      throw new NotFoundError(`Support case ${caseId} was not found`);
    }
    return supportCase;
  }

  private async requireCase(caseId: string): Promise<SupportCase> {
    return (await this.requireVersionedCase(caseId)).aggregate;
  }

  private async requireVersionedCase(caseId: string): Promise<Readonly<{ aggregate: SupportCase; version: bigint | undefined }>> {
    const persisted = await this.repository?.findCase(caseId);
    if (persisted) {
      return persisted;
    }
    const supportCase = this.cases.get(caseId);
    if (!supportCase) {
      throw new NotFoundError(`Support case ${caseId} was not found`);
    }
    return { aggregate: supportCase, version: undefined };
  }

  private async findTimeline(caseId: string): Promise<CaseTimeline | undefined> {
    return (await this.findVersionedTimeline(caseId))?.aggregate;
  }

  private async findVersionedTimeline(caseId: string): Promise<Readonly<{ aggregate: CaseTimeline; version: bigint }> | undefined> {
    return await this.repository?.findTimeline(caseId) ?? (this.timelines.has(caseId) ? { aggregate: this.timelines.get(caseId)!, version: 0n } : undefined);
  }

  private async findManualAction(manualActionId: string): Promise<Readonly<{ aggregate: ManualActionRequest; version: bigint | undefined }> | undefined> {
    const persisted = await this.repository?.findManualAction(manualActionId);
    if (persisted) {
      return persisted;
    }
    const action = this.manualActions.get(manualActionId);
    return action ? { aggregate: action, version: undefined } : undefined;
  }

  private async findCasesReferencingEnvelope(envelope: EventEnvelope): Promise<SupportCase[]> {
    if (!this.repository) {
      return [...this.cases.values()];
    }
    return (await this.repository.listCases()).filter((supportCase) => caseReferencesEnvelope(supportCase.toSnapshot(), envelope));
  }
}

export function isDomainError(error: unknown): error is DomainError {
  return error instanceof DomainError;
}

function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { code?: unknown }).code === "23505";
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
