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
        ...(isReq311Template(event.templateCode) ? { templateType: event.templateCode } : {}),
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
    case "NotificationCancelled":
      return {
        notificationTaskId: event.notificationTaskId,
        reason: event.reason,
        cancelledAt: event.cancelledAt.toISOString(),
      };
  }
}

function isReq311Template(templateCode: string): boolean {
  return [
    "ORDER_CONFIRMED",
    "PAYMENT_REMINDER",
    "TICKET_ISSUED",
    "DELAY_ALERT",
    "REFUND_COMPLETED",
    "WAITLIST_PROMOTED",
    "DISRUPTION_ALERT",
    "RECOVERY_CASE_OPENED",
    "RECOVERY_OPTIONS_AVAILABLE",
    "RECOVERY_OPTION_SELECTED",
    "RECOVERY_EXECUTION_STARTED",
    "RECOVERY_REFUND_EXECUTED",
    "RECOVERY_COMPENSATION_ISSUED",
    "RECOVERY_REACCOMMODATION",
    "RECOVERY_COMPLETED",
    "RECOVERY_FAILED",
    "TRANSFER_AT_RISK",
    "CONNECTION_MISSED",
    "CONNECTION_RECOVERED",
    "CONNECTION_REACCOMMODATED",
    "DISRUPTION_REBOOK",
    "ANCILLARY_OFFER_QUOTED",
    "ANCILLARY_OFFER_EXPIRED",
    "ANCILLARY_ORDER_ITEM_SELECTED",
    "ANCILLARY_ORDER_ITEM_PENDING_CONFIRMATION",
    "ANCILLARY_ORDER_ITEM_CONFIRMED",
    "ANCILLARY_ORDER_ITEM_FULFILLMENT_READY",
    "ANCILLARY_ORDER_ITEM_FULFILLED",
    "ANCILLARY_ORDER_ITEM_FAILED",
    "ANCILLARY_ORDER_ITEM_CANCELLED",
    "ANCILLARY_ORDER_ITEM_REFUND_PENDING",
    "ANCILLARY_ORDER_ITEM_REFUNDED",
    "ANCILLARY_FULFILLMENT_FACT_RECORDED",
    "DISPATCH_REQUESTED",
    "DRIVER_ASSIGNED",
    "DRIVER_ETA_UPDATED",
    "DRIVER_ARRIVED",
    "RIDE_STARTED",
    "RIDE_ENDED",
    "DRIVER_CANCELLED",
    "DISPATCH_USER_CANCELLED",
    "DISPATCH_NO_SHOW_RECORDED",
    "DISPATCH_FAILED",
  ].includes(templateCode);
}
