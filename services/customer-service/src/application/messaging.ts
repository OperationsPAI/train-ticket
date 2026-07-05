import type { CustomerServiceDomainEvent } from "../domain.js";
import {
  ConsumedEventDeduplicator,
  HandlerError,
  InMemoryEventPublisher,
  InMemoryEventSubscriber,
  PublishFailed,
  SubscribeFailed,
  createEventEnvelope,
  newCommandId,
  newCorrelationId,
  type EventEnvelope as KitEventEnvelope,
  type EventPublisher,
  type EventSubscriber,
} from "@trainticket/ts-kit";

export {
  ConsumedEventDeduplicator,
  HandlerError,
  InMemoryEventPublisher,
  InMemoryEventSubscriber,
  PublishFailed,
  SubscribeFailed,
  newCommandId,
  newCorrelationId,
  type EventEnvelope as KitEventEnvelope,
  type EventPublisher,
  type EventSubscriber,
};

export type EventEnvelope = KitEventEnvelope & Readonly<{ causationId: string }>;

export function toEventEnvelope(event: CustomerServiceDomainEvent, causationId: string = newCommandId()): EventEnvelope {
  return createEventEnvelope({
    eventId: event.eventId,
    eventType: event.eventType,
    occurredAt: event.occurredAt,
    correlationId: event.correlationId,
    causationId: event.causationId ?? causationId,
    producer: event.producer,
    schemaVersion: event.schemaVersion,
    payload: domainEventPayload(event),
  }) as EventEnvelope;
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
