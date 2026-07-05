import type { NotificationDomainEvent } from "../domain.js";

export type EventEnvelope<TPayload extends Record<string, unknown> = Record<string, unknown>> = Readonly<{
  eventId: string;
  eventType: string;
  schemaVersion: number;
  producer: string;
  causationId?: string;
  correlationId: string;
  occurredAt: string;
  payload: TPayload;
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

export type HandlerErrorKind = "transient" | "fatal";

export type EventHandlerResult =
  | Readonly<{ ok: true }>
  | Readonly<{ ok: false; kind: HandlerErrorKind; error?: Error }>;

export type EventHandler = (envelope: EventEnvelope) => Promise<EventHandlerResult> | EventHandlerResult;

export interface EventPublisher {
  publish(envelope: EventEnvelope): Promise<void>;
}

export interface EventSubscriber {
  subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
  ): Promise<void>;
  stop?(): Promise<void>;
}

export function successfulHandling(): EventHandlerResult {
  return { ok: true };
}

export function transientHandling(error?: Error): EventHandlerResult {
  return { ok: false, kind: "transient", error };
}

export function fatalHandling(error?: Error): EventHandlerResult {
  return { ok: false, kind: "fatal", error };
}

export function toEventEnvelope(event: NotificationDomainEvent): EventEnvelope {
  return {
    eventId: event.eventId,
    eventType: event.eventType,
    schemaVersion: event.schemaVersion,
    producer: event.producer,
    causationId: event.causationId,
    correlationId: event.correlationId,
    occurredAt: event.occurredAt.toISOString(),
    payload: domainEventPayload(event),
  };
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

export class InMemoryEventPublisher implements EventPublisher {
  public readonly envelopes: EventEnvelope[] = [];

  async publish(envelope: EventEnvelope): Promise<void> {
    this.envelopes.push(structuredClone(envelope));
  }
}

export class DeduplicatingEventHandler {
  private readonly consumedEventIds = new Set<string>();

  constructor(private readonly delegate: EventHandler) {}

  async handle(envelope: EventEnvelope): Promise<EventHandlerResult> {
    if (this.consumedEventIds.has(envelope.eventId)) {
      return successfulHandling();
    }

    const result = await this.delegate(envelope);
    if (result.ok) {
      this.consumedEventIds.add(envelope.eventId);
    }
    return result;
  }

  hasConsumed(eventId: string): boolean {
    return this.consumedEventIds.has(eventId);
  }
}
