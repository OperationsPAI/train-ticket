import { randomUUID } from "node:crypto";

import type { CustomerServiceDomainEvent } from "../domain.js";

export type EventEnvelope = Readonly<{
  eventId: string;
  eventType: string;
  occurredAt: string;
  correlationId: string;
  causationId: string;
  producer: string;
  schemaVersion: number;
  payload: Readonly<Record<string, unknown>>;
}>;

export class PublishFailed extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "PublishFailed";
  }
}

export class SubscribeFailed extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "SubscribeFailed";
  }
}

export class HandlerError extends Error {
  constructor(
    public readonly kind: "transient" | "fatal",
    message: string,
    options?: ErrorOptions,
  ) {
    super(message, options);
    this.name = "HandlerError";
  }
}

export interface EventPublisher {
  publish(envelope: EventEnvelope): Promise<void>;
}

export interface EventSubscriber {
  subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void>;
}

export function toEventEnvelope(event: CustomerServiceDomainEvent, causationId: string = newCommandId()): EventEnvelope {
  return Object.freeze({
    eventId: canonicalEventId(event.eventId),
    eventType: event.eventType,
    occurredAt: event.occurredAt.toISOString(),
    correlationId: canonicalCorrelationId(event.correlationId),
    causationId: canonicalCausationId(event.causationId ?? causationId),
    producer: event.producer,
    schemaVersion: event.schemaVersion,
    payload: Object.freeze(domainEventPayload(event)),
  });
}

export function newCommandId(): string {
  return `cmd-${randomUUID()}`;
}

export function newCorrelationId(): string {
  return `corr-${randomUUID()}`;
}

export class InMemoryEventPublisher implements EventPublisher {
  public readonly envelopes: EventEnvelope[] = [];

  async publish(envelope: EventEnvelope): Promise<void> {
    this.envelopes.push(envelope);
  }
}

export class ConsumedEventDeduplicator {
  private readonly consumedEventIds = new Set<string>();

  async handle(envelope: EventEnvelope, handler: (envelope: EventEnvelope) => Promise<void>): Promise<boolean> {
    if (this.consumedEventIds.has(envelope.eventId)) {
      return false;
    }
    await handler(envelope);
    this.consumedEventIds.add(envelope.eventId);
    return true;
  }
}

export class InMemoryEventSubscriber implements EventSubscriber {
  private handler?: (envelope: EventEnvelope) => Promise<void>;
  private readonly deduplicator: ConsumedEventDeduplicator;

  constructor(deduplicator: ConsumedEventDeduplicator = new ConsumedEventDeduplicator()) {
    this.deduplicator = deduplicator;
  }

  async subscribe(
    _streams: readonly string[],
    _group: string,
    _consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void> {
    this.handler = handler;
  }

  async emit(envelope: EventEnvelope): Promise<boolean> {
    if (!this.handler) {
      throw new SubscribeFailed("Subscriber has not been started");
    }
    return this.deduplicator.handle(envelope, this.handler);
  }
}

function domainEventPayload(event: CustomerServiceDomainEvent): Readonly<Record<string, unknown>> {
  switch (event.type) {
    case "SupportCaseOpened":
      return omitUndefined({
        caseId: event.caseId,
        requesterRef: event.requesterRef,
        channel: event.channel,
        classification: event.classification,
        priority: event.priority,
        description: event.description,
        businessReferences: serializeValue(event.businessReferences),
      });
    case "EvidenceAttached":
      return {
        evidenceId: event.evidenceId,
        caseId: event.caseId,
        evidenceType: event.evidenceType,
        reference: event.reference,
        summary: event.summary,
        accessLevel: event.accessLevel,
        attachedBy: event.attachedBy,
      };
    case "SupportCaseClassified":
      return {
        caseId: event.caseId,
        classification: event.classification,
        priority: event.priority,
        classifiedBy: event.classifiedBy,
      };
    case "SupportCaseAssigned":
      return omitUndefined({
        caseId: event.caseId,
        ownerQueue: event.ownerQueue,
        assignedTo: event.assignedTo,
        assignedBy: event.assignedBy,
      });
    case "ManualActionRequested":
      return omitUndefined({
        manualActionId: event.manualActionId,
        caseId: event.caseId,
        targetDomain: event.targetDomain,
        commandType: event.commandType,
        operatorRef: event.operatorRef,
        reason: event.reason,
        evidenceRefs: serializeValue(event.evidenceRefs),
        description: event.description,
        requiresApproval: event.requiresApproval,
      });
    case "ManualActionResultRecorded":
      return omitUndefined({
        manualActionId: event.manualActionId,
        caseId: event.caseId,
        outcome: event.outcome,
        resultSummary: event.resultSummary,
        approvalRef: event.approvalRef,
      });
    case "SupportCaseEscalated":
      return {
        caseId: event.caseId,
        targetQueue: event.targetQueue,
        reason: event.reason,
        escalatedBy: event.escalatedBy,
      };
    case "SupportCaseResolved":
      return {
        caseId: event.caseId,
        summary: event.summary,
        resolutionCode: event.resolutionCode,
        resolvedBy: event.resolvedBy,
      };
    case "SupportCaseClosed":
      return {
        caseId: event.caseId,
        reason: event.reason,
        closedBy: event.closedBy,
      };
    case "SupportCaseReopened":
      return {
        caseId: event.caseId,
        reason: event.reason,
        requesterRef: event.requesterRef,
      };
    case "CaseTimelineEntryAppended":
      return {
        entryId: event.entryId,
        caseId: event.caseId,
        eventTypeCode: event.eventTypeCode,
        visibility: event.visibility,
      };
  }
}

function omitUndefined(values: Record<string, unknown>): Readonly<Record<string, unknown>> {
  const payload: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values)) {
    if (value !== undefined) {
      payload[key] = value;
    }
  }
  return payload;
}

function serializeValue(value: unknown): unknown {
  if (value instanceof Date) {
    return value.toISOString();
  }
  if (Array.isArray(value)) {
    return value.map(serializeValue);
  }
  if (value && typeof value === "object") {
    const serialized: Record<string, unknown> = {};
    for (const [key, nested] of Object.entries(value as Record<string, unknown>)) {
      serialized[key] = serializeValue(nested);
    }
    return serialized;
  }
  return value;
}

function canonicalEventId(value: string): string {
  return value.startsWith("evt-") ? value : `evt-${value}`;
}

function canonicalCorrelationId(value: string): string {
  return value.startsWith("corr-") ? value : `corr-${value}`;
}

function canonicalCausationId(value: string): string {
  if (value.startsWith("cmd-") || value.startsWith("evt-")) {
    return value;
  }
  return `cmd-${value}`;
}
