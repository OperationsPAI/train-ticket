import { randomUUID } from "node:crypto";

import type { AccountDomainEvent } from "./domain.js";

export type EventEnvelope = Readonly<{
  eventId: string;
  eventType: string;
  schemaVersion: 1;
  producer: "account";
  causationId: string;
  correlationId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}>;

export type HandlerResult = Readonly<{ ok: true }> | Readonly<{ ok: false; errorType: "transient" | "fatal"; error?: Error }>;

export interface EventPublisher {
  publish(envelope: EventEnvelope): Promise<void>;
}

export interface EventSubscriber {
  subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<HandlerResult> | HandlerResult,
  ): Promise<void>;
  close(): Promise<void>;
}

export function toEventEnvelope(event: AccountDomainEvent, correlationId: string, causationId = `cmd-${randomUUID()}`): EventEnvelope {
  return {
    eventId: `evt-${randomUUID()}`,
    eventType: event.type,
    schemaVersion: 1,
    producer: "account",
    causationId,
    correlationId: canonicalCorrelationId(correlationId),
    occurredAt: event.occurredAt.toISOString(),
    payload: eventPayload(event),
  };
}

function canonicalCorrelationId(correlationId: string): string {
  return correlationId.startsWith("corr-") ? correlationId : `corr-${correlationId}`;
}

function eventPayload(event: AccountDomainEvent): Record<string, unknown> {
  switch (event.type) {
    case "AccountCreated":
      return { accountId: event.accountId };
    case "AccountFrozen":
      return { accountId: event.accountId, reason: event.reason, operator: event.operator, caseRef: event.caseRef };
    case "AccountUnfrozen":
      return { accountId: event.accountId, reason: event.reason };
    case "AccountClosureStarted":
      return { accountId: event.accountId, closureRequestId: event.closureRequestId };
    case "AccountClosed":
      return { accountId: event.accountId, closureRequestId: event.closureRequestId, final: event.final };
    case "SessionOpened":
      return { sessionId: event.sessionId, accountId: event.accountId };
    case "SessionRevoked":
      return { sessionId: event.sessionId, accountId: event.accountId, reason: event.reason };
    case "PreferenceUpdated":
      return { accountId: event.accountId, preferenceKey: event.preferenceKey, oldValue: event.oldValue, newValue: event.newValue };
  }
}
