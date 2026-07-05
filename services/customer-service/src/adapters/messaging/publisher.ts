import { Redis } from "ioredis";

import { PublishFailed, type EventEnvelope, type EventPublisher } from "../../application/messaging.js";
import { DEFAULT_REDIS_URL, STREAM_MAXLEN, streamForProducer } from "./stream-config.js";

export class RedisEventPublisher implements EventPublisher {
  constructor(private readonly redis: Redis = new Redis(process.env.REDIS_URL ?? DEFAULT_REDIS_URL)) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    const stream = streamForProducer(envelope.producer);
    const serializedEnvelope = JSON.stringify(envelope);
    let lastError: unknown;
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        await this.redis.xadd(stream, "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", serializedEnvelope);
        return;
      } catch (error) {
        lastError = error;
        if (attempt < 3) {
          await sleep(25 * 2 ** (attempt - 1));
        }
      }
    }
    throw new PublishFailed(`Failed to publish ${envelope.eventType}`, { cause: lastError });
  }

  async close(): Promise<void> {
    this.redis.disconnect();
  }
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
