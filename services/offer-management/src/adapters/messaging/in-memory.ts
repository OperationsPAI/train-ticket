// ---------------------------------------------------------------------------
// In-memory fake implementations of EventPublisher and EventSubscriber ports
// for use in unit tests (no live Redis required).
// ---------------------------------------------------------------------------

import { EventPublisher, EventSubscriber, PublishFailed, type EventEnvelope, type EventHandler } from "../../ports/messaging.js";

/**
 * In-memory fake EventPublisher that stores published envelopes in an array.
 */
export class InMemoryEventPublisher implements EventPublisher {
  public readonly published: EventEnvelope[] = [];
  public failNext = false;

  async publish(envelope: EventEnvelope): Promise<void> {
    if (this.failNext) {
      this.failNext = false;
      throw new PublishFailed("Simulated publish failure");
    }
    this.published.push(envelope);
  }

  /** Convenience: find all published envelopes for a given producer. */
  findByProducer(producer: string): EventEnvelope[] {
    return this.published.filter((e) => e.producer === producer);
  }

  /** Convenience: find all published envelopes of a given event type. */
  findByEventType(eventType: string): EventEnvelope[] {
    return this.published.filter((e) => e.eventType === eventType);
  }

  /** Reset state. */
  reset(): void {
    this.published.length = 0;
    this.failNext = false;
  }
}

/**
 * In-memory fake EventSubscriber that captures handler invocations.
 * Does not actually poll; instead, `simulateEvent` can be called to
 * trigger the handler with a given envelope.
 */
export class InMemoryEventSubscriber implements EventSubscriber {
  public readonly received: EventEnvelope[] = [];
  private handler: EventHandler | null = null;
  private readonly processedEventIds = new Set<string>();

  async subscribe(
    _streams: readonly string[],
    _group: string,
    _consumerName: string,
    handler: EventHandler,
    _signal: AbortSignal,
  ): Promise<void> {
    this.handler = handler;
  }

  /** Simulate receiving an event. Returns the handler result. */
  async simulateEvent(envelope: EventEnvelope): Promise<"ack" | "retry" | "dlq"> {
    this.received.push(envelope);
    if (this.processedEventIds.has(envelope.eventId)) {
      return "ack";
    }
    if (!this.handler) {
      this.processedEventIds.add(envelope.eventId);
      return "ack";
    }
    const result = await this.handler(envelope);
    if (result === "ack") {
      this.processedEventIds.add(envelope.eventId);
    }
    return result;
  }

  /** Reset state. */
  reset(): void {
    this.received.length = 0;
    this.handler = null;
    this.processedEventIds.clear();
  }
}
