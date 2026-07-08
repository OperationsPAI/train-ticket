import {
  type ChannelType,
  type NotificationTask,
  NotificationTask as NotificationTaskAggregate,
  newNotificationTaskId,
  newReceiptId,
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

export type ExternalTriggerResult = "delivered" | "cancelled" | "failed" | "ignored";

export type UserPreference = Readonly<{
  recipientRef: string;
  intent: string;
  channel: ChannelType;
  enabled: boolean;
}>;

export interface UserPreferenceRepository {
  isEnabled(recipientRef: string, intent: string, channel: ChannelType): Promise<boolean> | boolean;
}

export interface NotificationTaskStore {
  saveNew(task: NotificationTask): Promise<{ version: bigint }> | { version: bigint };
  save(task: NotificationTask, expectedVersion: bigint): Promise<{ version: bigint }> | { version: bigint };
}

export type DeliveryResult =
  | Readonly<{ ok: true; providerMessageId?: string }>
  | Readonly<{ ok: false; outcome: "Bounced" | "Rejected" | "Timeout" | "Expired"; providerCode?: string; providerMessage?: string }>;

export interface NotificationChannelGateway {
  send(task: ReturnType<NotificationTask["toSnapshot"]>): Promise<DeliveryResult> | DeliveryResult;
}

export class NonConformantNotificationTrigger extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NonConformantNotificationTrigger";
  }
}

export class DirectSuccessGateway implements NotificationChannelGateway {
  async send(task: ReturnType<NotificationTask["toSnapshot"]>): Promise<DeliveryResult> {
    if (task.channel === "IN_APP") {
      return { ok: true };
    }
    return { ok: true, providerMessageId: `${task.channel.toLowerCase()}-${task.notificationTaskId}` };
  }
}

class AllowAllPreferences implements UserPreferenceRepository {
  isEnabled(): boolean {
    return true;
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
  constructor(
    private readonly publisher: EventPublisher,
    private readonly preferences: UserPreferenceRepository = new AllowAllPreferences(),
    private readonly channelGateway: NotificationChannelGateway = new DirectSuccessGateway(),
    private readonly taskStore?: NotificationTaskStore,
  ) {}

  async handleExternalTrigger(envelope: EventEnvelope): Promise<ExternalTriggerResult> {
    const command = scheduleCommandFromEnvelope(envelope);
    if (command === undefined) {
      console.info(`Ignoring unsupported notification trigger ${envelope.eventType} (${envelope.eventId})`);
      return "ignored";
    }

    const { task, event: scheduled } = NotificationTaskAggregate.schedule(command);
    let version = (await this.taskStore?.saveNew(task))?.version;
    await this.publisher.publish(toEventEnvelope(scheduled));

    if (!command.transactionRequired && !await this.preferences.isEnabled(command.recipientRef, command.intent, command.channel)) {
      const { task: cancelled, event } = task.cancel({
        notificationTaskId: task.id,
        reason: "SUPPRESSED_BY_PREFERENCES",
        cancelledAt: new Date(),
      });
      if (version !== undefined) {
        version = (await this.taskStore?.save(cancelled, version))?.version ?? version;
      }
      await this.publisher.publish(toEventEnvelope(event));
      return "cancelled";
    }

    const dispatchedAt = new Date();
    const { task: delivering, event: dispatched } = task.dispatch({ notificationTaskId: task.id, dispatchedAt });
    if (version !== undefined) {
      version = (await this.taskStore?.save(delivering, version))?.version ?? version;
    }
    await this.publisher.publish(toEventEnvelope(dispatched));

    const delivery = await this.channelGateway.send(delivering.toSnapshot());
    if (delivery.ok) {
      const { task: delivered, event: deliveredEvent } = delivering.recordReceipt({
        receiptId: newReceiptId(),
        notificationTaskId: delivering.id,
        channel: command.channel,
        outcome: "Delivered",
        recordedAt: new Date(),
      });
      if (version !== undefined) {
        version = (await this.taskStore?.save(delivered, version))?.version ?? version;
      }
      await this.publisher.publish(toEventEnvelope(deliveredEvent));
      return "delivered";
    }

    const { task: failed, event: failedEvent } = delivering.recordReceipt({
      receiptId: newReceiptId(),
      notificationTaskId: delivering.id,
      channel: command.channel,
      outcome: delivery.outcome,
      providerCode: delivery.providerCode,
      providerMessage: delivery.providerMessage,
      recordedAt: new Date(),
    });
    if (version !== undefined) {
      await this.taskStore?.save(failed, version);
    }
    await this.publisher.publish(toEventEnvelope(failedEvent));
    return "failed";
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
    notificationTaskId: newNotificationTaskId(),
    triggerEventId: envelope.eventId,
    triggerEventType: envelope.eventType,
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
    recipientRef,
    templateCode: mapping.templateCode,
    channel: mapping.channel,
    intent: mapping.intent,
    transactionRequired: booleanValue(payload.transactionRequired) ?? true,
    variables: mapping.variables(payload),
    scheduledAt: new Date(),
    triggerBusinessRef: triggerBusinessRef(envelope),
  };
}

function triggerBusinessRef(envelope: EventEnvelope): string | undefined {
  const payload = envelope.payload;
  const businessRef = stringValue(payload.orderId)
    ?? stringValue(payload.journeyOrderId)
    ?? stringValue(payload.paymentIntentId)
    ?? stringValue(payload.refundId)
    ?? stringValue(payload.entitlementId)
    ?? stringValue(payload.segmentBookingId)
    ?? stringValue(payload.caseId)
    ?? stringValue(payload.postSalesCaseId)
    ?? stringValue(payload.businessRef);
  return businessRef ? `${envelope.eventType}:${businessRef}` : undefined;
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
    case "PostSalesEligibilityEvaluated":
      return postSalesMapping("post_sales_eligibility", "POST_SALES_ELIGIBILITY");
    case "PostSalesDecisionQuoted":
      return postSalesMapping("post_sales_decision", "POST_SALES_DECISION");
    case "PostSalesExecutionStarted":
      return postSalesMapping("post_sales_execution", "POST_SALES_EXECUTION");
    case "PostSalesApplied":
      return postSalesMapping("post_sales_applied", "POST_SALES_APPLIED");
    case "PostSalesFailed":
      return postSalesMapping("post_sales_failed", "POST_SALES_FAILED");
    case "ChangeApplied":
      return postSalesMapping("change_applied", "CHANGE_APPLIED");
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

function postSalesMapping(templateCode: string, intent: string): TriggerMapping {
  return {
    templateCode,
    intent,
    channel: "IN_APP",
    recipient: (payload) => recipientFromDirectFields(payload) ?? stringValue(payload.actorRef),
    variables: (payload) => pickStringVariables(payload, ["caseId", "orderId", "journeyOrderId", "reasonCode", "decisionKind", "approvalRef", "reason"]),
  };
}

function recipientFromOrderEvent(payload: Record<string, unknown>): string | undefined {
  return recipientFromDirectFields(payload) ?? recipientFromTravelerRefs(payload.travelerRefs);
}

function recipientFromDirectFields(payload: Record<string, unknown>): string | undefined {
  return stringValue(payload.recipientRef) ?? stringValue(payload.travelerId) ?? stringValue(payload.accountId) ?? stringValue(payload.actorRef);
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

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === "boolean" ? value : undefined;
}
