// ---------------------------------------------------------------------------
// Redis Streams adapter for EventPublisher
// Implements the port defined in ../../ports/messaging.ts
// Lives ONLY under adapters/messaging/ — no Redis types outside this module.
// ---------------------------------------------------------------------------

import { type Redis } from "ioredis";

import { EventPublisher, PublishFailed, type EventEnvelope } from "../../ports/messaging.js";

const MAX_RETRIES = 3;
const RETRY_DELAY_MS = 100; // base delay, doubles each retry

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export class RedisEventPublisher implements EventPublisher {
  constructor(private readonly redis: Redis) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    const streamKey = `events:${envelope.producer}`;
    const envelopeJson = JSON.stringify(envelope);
    let lastError: Error | undefined;

    for (let attempt = 0; attempt < MAX_RETRIES; attempt++) {
      try {
        await this.redis.xadd(streamKey, "MAXLEN", "~", "100000", "*", "envelope", envelopeJson);
        return;
      } catch (err) {
        lastError = err instanceof Error ? err : new Error(String(err));
        if (attempt < MAX_RETRIES - 1) {
          await sleep(RETRY_DELAY_MS * Math.pow(2, attempt));
        }
      }
    }

    throw new PublishFailed(
      `Failed to publish event ${envelope.eventId} to ${streamKey} after ${MAX_RETRIES} attempts`,
      lastError,
    );
  }
}
