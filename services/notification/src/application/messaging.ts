import type { NotificationDomainEvent } from "../domain.js";
import {
  DeduplicatingEventHandler,
  InMemoryEventPublisher,
  PublishFailed,
  SubscribeFailed,
  createEventEnvelope,
  fatalHandling,
  successfulHandling,
  transientHandling,
  type EventEnvelope,
  type EventHandler,
  type EventHandlerResult,
  type EventPublisher,
  type EventSubscriber,
} from "@trainticket/ts-kit";

export {
  DeduplicatingEventHandler,
  InMemoryEventPublisher,
  PublishFailed,
  SubscribeFailed,
  createEventEnvelope,
  fatalHandling,
  successfulHandling,
  transientHandling,
  type EventEnvelope,
  type EventHandler,
  type EventHandlerResult,
  type EventPublisher,
  type EventSubscriber,
};

export function toEventEnvelope(event: NotificationDomainEvent): EventEnvelope {
  return createEventEnvelope({
    eventId: event.eventId,
    eventType: event.eventType,
    schemaVersion: event.schemaVersion,
    producer: event.producer,
    causationId: event.causationId,
    correlationId: event.correlationId,
    occurredAt: event.occurredAt,
    payload: domainEventPayload(event),
  });
}

function domainEventPayload(event: NotificationDomainEvent): Record<string, unknown> {
  switch (event.type) {
    case "NotificationScheduled":
      return {
        notificationTaskId: event.notificationTaskId,
        templateCode: event.templateCode,
        recipientRef: event.recipientRef,
        channel: event.channel,
        intent: event.intent,
        transactionRequired: event.transactionRequired,
        scheduledAt: event.scheduledAt.toISOString(),
      };
    case "NotificationDispatched":
      return {
        notificationTaskId: event.notificationTaskId,
        channel: event.channel,
        dispatchedAt: event.dispatchedAt.toISOString(),
      };
    case "NotificationDelivered":
      return {
        notificationTaskId: event.notificationTaskId,
        channel: event.channel,
        receiptId: event.receiptId,
        outcome: event.outcome,
        deliveredAt: event.deliveredAt.toISOString(),
      };
    case "NotificationSent": {
      const payload: Record<string, unknown> = {
        notificationTaskId: event.notificationTaskId,
        channel: event.channel,
        sentAt: event.sentAt.toISOString(),
      };
      if (event.providerMessageId !== undefined) {
        payload.providerMessageId = event.providerMessageId;
      }
      return payload;
    }
    case "NotificationFailed": {
      const payload: Record<string, unknown> = {
        notificationTaskId: event.notificationTaskId,
        channel: event.channel,
        receiptId: event.receiptId,
        outcome: event.outcome,
        failedAt: event.failedAt.toISOString(),
      };
      if (event.providerCode !== undefined) {
        payload.providerCode = event.providerCode;
      }
      if (event.providerMessage !== undefined) {
        payload.providerMessage = event.providerMessage;
      }
      return payload;
    }
    case "NotificationSuppressed":
      return {
        notificationTaskId: event.notificationTaskId,
        recipientRef: event.recipientRef,
        channel: event.channel,
        intent: event.intent,
        reason: event.reason,
        suppressedAt: event.suppressedAt.toISOString(),
      };
    case "NotificationCancelled":
      return {
        notificationTaskId: event.notificationTaskId,
        reason: event.reason,
        cancelledAt: event.cancelledAt.toISOString(),
      };
  }
}
