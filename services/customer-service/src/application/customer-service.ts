import { randomUUID, createHash } from "node:crypto";

import {
  DomainError,
  EvidenceRef,
  SupportCase,
  type CaseChannel,
  type CasePriority,
  type CloseReason,
  type EvidenceAccessLevel,
  type EvidenceType,
  type SupportCaseSnapshot,
} from "../domain.js";
import { newCommandId, toEventEnvelope, type EventEnvelope, type EventPublisher } from "./messaging.js";

export type SupportCaseDetails = SupportCaseSnapshot &
  Readonly<{
    evidence: readonly ReturnType<EvidenceRef["toSnapshot"]>[];
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

export class NotFoundError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotFoundError";
  }
}

export class IdempotencyKeyReusedError extends Error {
  constructor() {
    super("Idempotency-Key was reused with a different request body");
    this.name = "IdempotencyKeyReusedError";
  }
}

export type CachedHttpResponse = Readonly<{
  statusCode: number;
  body: unknown;
}>;

export class IdempotencyStore {
  private readonly records = new Map<string, Readonly<{ fingerprint: string; response: CachedHttpResponse }>>();

  async execute(key: string, fingerprintSource: unknown, operation: () => Promise<CachedHttpResponse>): Promise<CachedHttpResponse> {
    const fingerprint = requestFingerprint(fingerprintSource);
    const existing = this.records.get(key);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new IdempotencyKeyReusedError();
      }
      return existing.response;
    }
    const response = await operation();
    this.records.set(key, { fingerprint, response });
    return response;
  }
}

export class CustomerServiceApplication {
  private readonly cases = new Map<string, SupportCase>();
  private readonly evidenceByCase = new Map<string, EvidenceRef[]>();
  private readonly consumedIntegrationEvents = new Map<string, Readonly<{ source: string; eventType: string; consumedAt: Date; payload: Readonly<Record<string, unknown>> }>>();

  constructor(private readonly publisher: EventPublisher) {}

  async openSupportCase(request: OpenSupportCaseRequest, correlationId: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const openedAt = new Date();
    const { case: supportCase, event } = SupportCase.open({
      caseId: `sc-${randomUUID()}`,
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
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return supportCase.toSnapshot();
  }

  getSupportCase(caseId: string): SupportCaseDetails {
    const supportCase = this.requireCase(caseId);
    return {
      ...supportCase.toSnapshot(),
      evidence: Object.freeze((this.evidenceByCase.get(caseId) ?? []).map((evidence) => evidence.toSnapshot())),
    };
  }

  async attachEvidence(caseId: string, request: AttachEvidenceRequest, correlationId: string, causationId = newCommandId()) {
    this.requireCase(caseId);
    const { evidence, event } = EvidenceRef.attach({
      evidenceId: `evid-${randomUUID()}`,
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

  async classifySupportCase(caseId: string, request: ClassifySupportCaseRequest, operatorRef: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.classify({
      caseId,
      classification: request.classification,
      priority: request.priority,
      classifiedBy: operatorRef,
      classifiedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async assignSupportCase(caseId: string, request: AssignSupportCaseRequest, operatorRef: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.assign({
      caseId,
      ownerQueue: request.ownerQueue,
      assignedTo: request.assignedTo,
      assignedBy: operatorRef,
      assignedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async escalateCase(caseId: string, request: EscalateCaseRequest, operatorRef: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.escalate({
      caseId,
      targetQueue: request.targetQueue,
      reason: request.reason,
      escalatedBy: operatorRef,
      escalatedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async resolveCase(caseId: string, request: ResolveCaseRequest, operatorRef: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.resolve({
      caseId,
      summary: request.summary,
      resolutionCode: request.resolutionCode,
      resolvedBy: operatorRef,
      resolvedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async closeCase(caseId: string, request: CloseCaseRequest, operatorRef: string, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.close({
      caseId,
      reason: request.reason,
      closedBy: operatorRef,
      closedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async reopenCase(caseId: string, request: ReopenCaseRequest, causationId = newCommandId()): Promise<SupportCaseSnapshot> {
    const current = this.requireCase(caseId);
    const { case: updated, event } = current.reopen({
      caseId,
      reason: request.reason,
      requesterRef: request.requesterRef,
      reopenedAt: new Date(),
    });
    this.cases.set(caseId, updated);
    await this.publisher.publish(toEventEnvelope(event, causationId));
    return updated.toSnapshot();
  }

  async handleIntegrationEvent(envelope: EventEnvelope): Promise<void> {
    if (this.consumedIntegrationEvents.has(envelope.eventId)) {
      return;
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

  private requireCase(caseId: string): SupportCase {
    const supportCase = this.cases.get(caseId);
    if (!supportCase) {
      throw new NotFoundError(`Support case ${caseId} was not found`);
    }
    return supportCase;
  }
}

export function requestFingerprint(value: unknown): string {
  return createHash("sha256").update(stableJson(value)).digest("hex");
}

function stableJson(value: unknown): string {
  if (Array.isArray(value)) {
    return `[${value.map(stableJson).join(",")}]`;
  }
  if (value && typeof value === "object") {
    return `{${Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, nested]) => `${JSON.stringify(key)}:${stableJson(nested)}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

export function isDomainError(error: unknown): error is DomainError {
  return error instanceof DomainError;
}
