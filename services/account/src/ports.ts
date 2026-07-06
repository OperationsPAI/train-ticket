import type { AccountDomainEvent } from "./domain.js";
import { createEventEnvelope, newCommandId, type EventEnvelope, type EventPublisher, type EventSubscriber, type HandlerResult } from "@trainticket/ts-kit";

export type { EventEnvelope, EventPublisher, EventSubscriber, HandlerResult };

export function toEventEnvelope(event: AccountDomainEvent, correlationId: string, causationId = newCommandId()): EventEnvelope {
  return createEventEnvelope({
    eventType: event.type,
    schemaVersion: 1,
    producer: "account",
    causationId,
    correlationId,
    occurredAt: event.occurredAt,
    payload: eventPayload(event),
  });
}

function eventPayload(event: AccountDomainEvent): Record<string, unknown> {
  switch (event.type) {
    case "AccountCreated":
      return withEventMetadata(event, { accountId: event.accountId });
    case "AccountFrozen":
      return withEventMetadata(event, {
        accountId: event.accountId,
        reason: event.reason,
        operator: event.operator,
        caseRef: event.caseRef,
      });
    case "AccountUnfrozen":
      return withEventMetadata(event, { accountId: event.accountId, reason: event.reason });
    case "AccountClosureStarted":
      return withEventMetadata(event, { accountId: event.accountId, closureRequestId: event.closureRequestId });
    case "AccountClosed":
      return withEventMetadata(event, {
        accountId: event.accountId,
        closureRequestId: event.closureRequestId,
        final: event.final,
      });
    case "SessionOpened":
      return { sessionId: event.sessionId, accountId: event.accountId };
    case "SessionRevoked":
      return { sessionId: event.sessionId, accountId: event.accountId, reason: event.reason };
    case "PreferenceUpdated":
      return { accountId: event.accountId, preferenceKey: event.preferenceKey, oldValue: event.oldValue, newValue: event.newValue };
  }
}

function withEventMetadata(
  event: Extract<AccountDomainEvent, { accountId: string }>,
  payload: Record<string, unknown>,
): Record<string, unknown> {
  return {
    ...payload,
    occurredAt: event.occurredAt.toISOString(),
    correlationId: event.correlationId,
  };
}
