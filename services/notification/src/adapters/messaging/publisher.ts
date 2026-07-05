import { Redis } from "ioredis";

import { type EventEnvelope, type EventPublisher, PublishFailed } from "../../application/messaging.js";
import { redisUrl, streamForProducer } from "./stream-config.js";

const RETENTION_MAXLEN = 100_000;
const PUBLISH_ATTEMPTS = 3;

export class RedisStreamEventPublisher implements EventPublisher {
  constructor(private readonly redis = new Redis(redisUrl(), { lazyConnect: true })) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    const stream = streamForProducer(envelope.producer);
    const serialized = JSON.stringify(envelope);
    let lastError: unknown;

    for (let attempt = 1; attempt <= PUBLISH_ATTEMPTS; attempt += 1) {
      try {
        await this.ensureConnected();
        await this.redis.xadd(stream, "MAXLEN", "~", RETENTION_MAXLEN, "*", "envelope", serialized);
        return;
      } catch (error) {
        lastError = error;
        if (attempt < PUBLISH_ATTEMPTS) {
          await delay(25 * 2 ** (attempt - 1));
        }
      }
    }

    throw new PublishFailed(`Failed to publish ${envelope.eventType}`, { cause: lastError });
  }

  async close(): Promise<void> {
    this.redis.disconnect();
  }

  private async ensureConnected(): Promise<void> {
    if (this.redis.status === "wait") {
      await this.redis.connect();
    }
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
