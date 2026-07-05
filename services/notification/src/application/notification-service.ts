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

export class NotificationApplicationService {
  constructor(private readonly publisher: EventPublisher) {}

  async handleExternalTrigger(envelope: EventEnvelope): Promise<void> {
    const command = scheduleCommandFromEnvelope(envelope);
    const { event } = NotificationTask.schedule(command);
    await this.publisher.publish(toEventEnvelope(event));
  }
}

function scheduleCommandFromEnvelope(envelope: EventEnvelope): ScheduleNotification {
  const payload = envelope.payload;
  const recipientRef = stringValue(payload.recipientRef) ?? stringValue(payload.travelerId) ?? "usr-unknown";
  const templateCode = templateCodeFor(envelope.eventType);
  const scheduledAt = new Date();

  return {
    notificationTaskId: `nt-${crypto.randomUUID()}`,
    triggerEventId: envelope.eventId,
    triggerEventType: envelope.eventType,
    correlationId: envelope.correlationId,
    causationId: envelope.eventId,
    recipientRef,
    templateCode,
    channel: channelFor(envelope.eventType),
    intent: intentFor(envelope.eventType),
    transactionRequired: true,
    variables: stringifyPayload(payload),
    scheduledAt,
  };
}

function stringValue(value: unknown): string | undefined {
  return typeof value === "string" && value.trim().length > 0 ? value : undefined;
}

function stringifyPayload(payload: Record<string, unknown>): Record<string, string> {
  const variables: Record<string, string> = {};
  for (const [key, value] of Object.entries(payload)) {
    if (typeof value === "string") {
      variables[key] = value;
    } else if (typeof value === "number" || typeof value === "boolean") {
      variables[key] = String(value);
    }
  }
  return variables;
}

function templateCodeFor(eventType: string): string {
  return eventType.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
}

function intentFor(eventType: string): string {
  return eventType.replace(/([a-z0-9])([A-Z])/g, "$1_$2").toUpperCase();
}

function channelFor(_eventType: string): ChannelType {
  return "IN_APP";
}
