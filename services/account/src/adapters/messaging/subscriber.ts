import { Redis } from "ioredis";

import type { EventEnvelope, EventSubscriber, HandlerResult } from "../../ports.js";
import { moveToDlq } from "./dlq-handler.js";
import {
  accountConsumerGroup,
  accountConsumerName,
  accountStream,
  maxDeliveryAttempts,
  pollBlockMs,
  pollCount,
  recoveryMinIdleMs,
} from "./stream-config.js";

type StreamEntry = [string, string[]];
type StreamRead = Array<[string, StreamEntry[]]> | null;
type AutoClaimResult = [string, StreamEntry[], unknown?];

export class SubscribeFailed extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "SubscribeFailed";
  }
}

export class RedisStreamEventSubscriber implements EventSubscriber {
  private closed = false;
  private readonly consumedEventIds = new Set<string>();
  private recoveryTimer?: NodeJS.Timeout;

  constructor(
    private readonly redis: Redis = new Redis(process.env.REDIS_URL ?? "redis://localhost:6379"),
  ) {}

  async subscribe(
    streams: readonly string[] = [accountStream],
    group = accountConsumerGroup,
    consumerName = accountConsumerName(),
    handler: (envelope: EventEnvelope) => Promise<HandlerResult> | HandlerResult,
  ): Promise<void> {
    try {
      await this.ensureGroups(streams, group);
    } catch (error) {
      throw new SubscribeFailed("Failed to start account event subscriber", { cause: error });
    }

    this.recoveryTimer = setInterval(() => {
      void this.recover(streams, group, consumerName, handler);
    }, recoveryMinIdleMs);

    void this.poll(streams, group, consumerName, handler);
  }

  async close(): Promise<void> {
    this.closed = true;
    if (this.recoveryTimer) {
      clearInterval(this.recoveryTimer);
    }
    this.redis.disconnect();
  }

  private async ensureGroups(streams: readonly string[], group: string): Promise<void> {
    for (const stream of streams) {
      try {
        await this.redis.xgroup("CREATE", stream, group, "$", "MKSTREAM");
      } catch (error) {
        if (!String(error).includes("BUSYGROUP")) {
          throw error;
        }
      }
    }
  }

  private async poll(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<HandlerResult> | HandlerResult,
  ): Promise<void> {
    while (!this.closed) {
      const response = await this.redis.call(
        "XREADGROUP",
        "GROUP",
        group,
        consumerName,
        "BLOCK",
        pollBlockMs,
        "COUNT",
        pollCount,
        "STREAMS",
        ...streams,
        ...streams.map(() => ">"),
      ) as StreamRead;
      await this.processRead(response, group, handler);
    }
  }

  private async recover(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<HandlerResult> | HandlerResult,
  ): Promise<void> {
    for (const stream of streams) {
      const claimed = await this.redis.xautoclaim(stream, group, consumerName, recoveryMinIdleMs, "0", "COUNT", 100) as AutoClaimResult;
      for (const entry of claimed[1] ?? []) {
        const attempts = await this.deliveryAttempts(stream, group, entry[0]);
        if (attempts >= maxDeliveryAttempts) {
          await this.deadLetterAndAck(stream, group, entry);
        } else {
          await this.processEntry(stream, group, entry, handler);
        }
      }
    }
  }

  private async processRead(response: StreamRead, group: string, handler: (envelope: EventEnvelope) => Promise<HandlerResult> | HandlerResult): Promise<void> {
    for (const [stream, entries] of response ?? []) {
      for (const entry of entries) {
        await this.processEntry(stream, group, entry, handler);
      }
    }
  }

  private async processEntry(stream: string, group: string, entry: StreamEntry, handler: (envelope: EventEnvelope) => Promise<HandlerResult> | HandlerResult): Promise<void> {
    const serializedEnvelope = field(entry[1], "envelope");
    if (!serializedEnvelope) {
      await this.redis.xack(stream, group, entry[0]);
      return;
    }

    let envelope: EventEnvelope;
    try {
      envelope = JSON.parse(serializedEnvelope) as EventEnvelope;
    } catch {
      await this.deadLetterAndAck(stream, group, entry);
      return;
    }

    if (this.consumedEventIds.has(envelope.eventId)) {
      await this.redis.xack(stream, group, entry[0]);
      return;
    }

    const result = await handler(envelope);
    if (result.ok) {
      this.consumedEventIds.add(envelope.eventId);
      await this.redis.xack(stream, group, entry[0]);
      return;
    }

    if (result.errorType === "fatal") {
      await this.deadLetterAndAck(stream, group, entry);
    }
  }

  private async deadLetterAndAck(stream: string, group: string, entry: StreamEntry): Promise<void> {
    const serializedEnvelope = field(entry[1], "envelope") ?? "{}";
    await moveToDlq(this.redis, stream, serializedEnvelope);
    await this.redis.xack(stream, group, entry[0]);
  }

  private async deliveryAttempts(stream: string, group: string, entryId: string): Promise<number> {
    const pending = await this.redis.xpending(stream, group, entryId, entryId, 1) as unknown[];
    const row = pending[0] as unknown[] | undefined;
    const attempts = row?.[3];
    return typeof attempts === "number" ? attempts : 1;
  }
}

function field(fields: string[], name: string): string | undefined {
  for (let index = 0; index < fields.length; index += 2) {
    if (fields[index] === name) {
      return fields[index + 1];
    }
  }
  return undefined;
}
