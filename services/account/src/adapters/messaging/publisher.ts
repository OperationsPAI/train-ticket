import { Redis } from "ioredis";

import type { EventEnvelope, EventPublisher } from "../../ports.js";
import { accountStream, maxStreamLength } from "./stream-config.js";

export class PublishFailed extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "PublishFailed";
  }
}

export class RedisStreamEventPublisher implements EventPublisher {
  constructor(private readonly redis: Redis = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379")) {}

  async publish(envelope: EventEnvelope): Promise<void> {
    const serialized = JSON.stringify(envelope);
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      try {
        await this.redis.xadd(accountStream, "MAXLEN", "~", maxStreamLength, "*", "envelope", serialized);
        return;
      } catch (error) {
        if (attempt === 3) {
          throw new PublishFailed("Failed to publish account event", { cause: error });
        }
        await delay(25 * 2 ** (attempt - 1));
      }
    }
  }

  async close(): Promise<void> {
    this.redis.disconnect();
  }
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
