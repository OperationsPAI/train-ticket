import crypto from "node:crypto";

import {
  type ChannelType,
  NotificationTask,
  type ScheduleNotification,
} from "../domain.js";
import { type EventEnvelope, type EventPublisher, toEventEnvelope } from "./messaging.js";

export type ExternalNotificationTrigger = Readonly<{
  eventId: string;
  eventType: string;
  correlationId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}>;

export type ExternalTriggerResult = "scheduled" | "ignored";

export class NonConformantNotificationTrigger extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NonConformantNotificationTrigger";
  }
}

type TriggerMapping = Readonly<{
  templateCode: string;
  intent: string;
  channel: ChannelType;
  recipient: (payload: Record<string, unknown>) => string | undefined;
  variables: (payload: Record<string, unknown>) => Record<string, string>;
}>;

export class NotificationApplicationService {
  constructor(private readonly publisher: EventPublisher) {}

  async handleExternalTrigger(envelope: EventEnvelope): Promise<ExternalTriggerResult> {
    const command = scheduleCommandFromEnvelope(envelope);
    if (command === undefined) {
      console.info(`Ignoring unsupported notification trigger ${envelope.eventType} (${envelope.eventId})`);
      return "ignored";
    }

    const { event } = NotificationTask.schedule(command);
    await this.publisher.publish(toEventEnvelope(event));
    return "scheduled";
  }
}

function scheduleCommandFromEnvelope(envelope: EventEnvelope): ScheduleNotification | undefined {
  const mapping = mappingFor(envelope.eventType);
  if (mapping === undefined) {
    return undefined;
  }

  const payload = envelope.payload;
  const recipientRef = mapping.recipient(payload);
  if (recipientRef === undefined) {
    throw new NonConformantNotificationTrigger(
      `Notification trigger ${envelope.eventType} (${envelope.eventId}) does not contain a resolvable recipientRef`,
    );
  }

  return {
    notificationTaskId: `nt-${crypto.randomUUID()}`,
    triggerEventId: envelope.eventId,
    triggerEventType: envelope.eventType,
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
    recipientRef,
    templateCode: mapping.templateCode,
    channel: mapping.channel,
    intent: mapping.intent,
    transactionRequired: true,
    variables: mapping.variables(payload),
    scheduledAt: new Date(),
  };
}

function mappingFor(eventType: string): TriggerMapping | undefined {
  switch (eventType) {
    case "JourneyOrderCreated":
      return orderMapping("order_created", "ORDER_CREATED");
    case "JourneyOrderPendingPayment":
      return orderMapping("order_pending_payment", "ORDER_PENDING_PAYMENT");
    case "JourneyOrderPaymentRecorded":
      return orderMapping("order_payment_recorded", "ORDER_PAYMENT_RECORDED");
    case "JourneyOrderConfirmed":
      return orderMapping("order_confirmed", "ORDER_CONFIRMED");
    case "JourneyOrderCancelled":
      return orderMapping("order_cancelled", "ORDER_CANCELLED");
    case "JourneyOrderPostSalesAdjusted":
    case "JourneyOrderAdjusted":
      return orderMapping("order_adjusted", "ORDER_ADJUSTED");
    case "PaymentCaptured":
      return paymentMapping("payment_captured", "PAYMENT_RESULT");
    case "PaymentFailed":
    case "PaymentIntentFailed":
      return paymentMapping("payment_failed", "PAYMENT_RESULT");
    case "PaymentIntentExpired":
    case "PaymentExpired":
      return paymentMapping("payment_expired", "PAYMENT_RESULT");
    case "RefundSettled":
      return refundMapping();
    case "EntitlementIssued":
      return ticketIssuedMapping();
    default:
      return undefined;
  }
}

function orderMapping(templateCode: string, intent: string): TriggerMapping {
  return {
    templateCode,
    intent,
    channel: "IN_APP",
    recipient: recipientFromOrderEvent,
    variables: (payload) => pickStringVariables(payload, ["orderId", "journeyOrderId", "offerId", "paymentIntentId", "postSalesCaseId", "reason"]),
  };
}

function paymentMapping(templateCode: string, intent: string): TriggerMapping {
  return {
    templateCode,
    intent,
    channel: "IN_APP",
    recipient: recipientFromDirectFields,
    variables: (payload) => pickStringVariables(payload, ["paymentIntentId", "orderId", "businessRef", "channel", "channelTransactionId", "reason", "reasonCode"]),
  };
}

function refundMapping(): TriggerMapping {
  return {
    templateCode: "refund_settled",
    intent: "REFUND_SETTLED",
    channel: "IN_APP",
    recipient: recipientFromDirectFields,
    variables: (payload) => pickStringVariables(payload, ["refundId", "paymentIntentId", "orderId", "reason"]),
  };
}

function ticketIssuedMapping(): TriggerMapping {
  return {
    templateCode: "ticket_issued",
    intent: "TICKET_ISSUED",
    channel: "IN_APP",
    recipient: (payload) => recipientFromDirectFields(payload) ?? stringValue(payload.travelerRef),
    variables: (payload) => pickStringVariables(payload, ["entitlementId", "journeyOrderId", "orderId", "orderItemId", "segmentBookingId", "segmentRef", "credentialNo", "credentialType"]),
  };
}

function recipientFromOrderEvent(payload: Record<string, unknown>): string | undefined {
  return recipientFromDirectFields(payload) ?? recipientFromTravelerRefs(payload.travelerRefs);
}

function recipientFromDirectFields(payload: Record<string, unknown>): string | undefined {
  return stringValue(payload.recipientRef) ?? stringValue(payload.travelerId);
}

function recipientFromTravelerRefs(value: unknown): string | undefined {
  if (!Array.isArray(value)) {
    return undefined;
  }

  for (const candidate of value) {
    if (typeof candidate === "string" && candidate.trim().length > 0) {
      return candidate;
    }
    if (candidate && typeof candidate === "object") {
      const ref = candidate as Record<string, unknown>;
      const recipientRef = stringValue(ref.recipientRef) ?? stringValue(ref.travelerId) ?? stringValue(ref.travelerRef) ?? stringValue(ref.id);
      if (recipientRef !== undefined) {
        return recipientRef;
      }
    }
  }
  return undefined;
}

function pickStringVariables(payload: Record<string, unknown>, keys: readonly string[]): Record<string, string> {
  const variables: Record<string, string> = {};
  for (const key of keys) {
    const value = payload[key];
    if (typeof value === "string" && value.trim().length > 0) {
      variables[key] = value;
    } else if (typeof value === "number" || typeof value === "boolean") {
      variables[key] = String(value);
    }
  }
  return variables;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}
