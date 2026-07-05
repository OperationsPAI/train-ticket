// ---------------------------------------------------------------------------
// EventEnvelope — shared envelope for all domain events
// Per shared-primitives.md §1 and messaging.md Abstract Ports
// ---------------------------------------------------------------------------

export type EventEnvelope = Readonly<{
  eventId: string;
  eventType: string;
  schemaVersion: number;
  producer: string;
  causationId?: string;
  correlationId: string;
  occurredAt: string;
  payload: Record<string, unknown>;
}>;

// ---------------------------------------------------------------------------
// EventPublisher port — application layer depends ONLY on this interface
// ---------------------------------------------------------------------------

export class PublishFailed extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "PublishFailed";
  }
}

export interface EventPublisher {
  /**
   * Publish a domain event to the event bus.
   *
   * - The implementation MUST determine the target stream from the `producer`
   *   field of the envelope (stream = "events:<producer>").
   * - The implementation MUST serialize the entire envelope as a single JSON
   *   value in the "envelope" field of the Redis Stream entry.
   * - The implementation MUST apply the retention policy (MAXLEN ~ 100000).
   * - On transient failure, retry with exponential backoff (3 attempts).
   * - On persistent failure, throw PublishFailed.
   */
  publish(envelope: EventEnvelope): Promise<void>;
}

// ---------------------------------------------------------------------------
// EventSubscriber port — application layer depends ONLY on this interface
// ---------------------------------------------------------------------------

export class SubscribeFailed extends Error {
  constructor(message: string, public readonly cause?: unknown) {
    super(message);
    this.name = "SubscribeFailed";
  }
}

export type HandlerResult = "ack" | "retry" | "dlq";

/**
 * Event handler callback — returns a result indicating how the adapter
 * should handle the message (ack, retry, or move to DLQ).
 */
export type EventHandler = (envelope: EventEnvelope) => Promise<HandlerResult>;

export interface EventSubscriber {
  /**
   * Subscribe to one or more event streams as a consumer group member.
   *
   * The implementation runs in the background and calls the handler for each
   * received message. The handler returns ack/retry/dlq to guide ack behaviour.
   *
   * Lifecycle: the subscriber runs until the provided AbortSignal is triggered.
   */
  subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
    signal: AbortSignal,
  ): Promise<void>;
}
