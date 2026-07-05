import { Redis } from "ioredis";

import {
  HandlerError,
  SubscribeFailed,
  type EventEnvelope,
  type EventSubscriber,
} from "../../application/messaging.js";
import {
  DEFAULT_REDIS_URL,
  MAX_DELIVERY_ATTEMPTS,
  RECOVERY_MIN_IDLE_MS,
  STREAM_MAXLEN,
  dlqForStream,
} from "./stream-config.js";

type StreamEntry = [id: string, fields: string[]];
type StreamMessages = [stream: string, entries: StreamEntry[]];

export type SubscriberLoopFailureHandler = (error: unknown) => void;

export class RedisEventSubscriber implements EventSubscriber {
  private stopped = false;
  private readonly consumedEventIds = new Set<string>();
  private readonly backgroundLoops = new Set<Promise<void>>();

  constructor(
    private readonly redis: Redis = new Redis(process.env.REDIS_URL ?? DEFAULT_REDIS_URL),
    private readonly onLoopFailure: SubscriberLoopFailureHandler = defaultLoopFailureHandler,
  ) {}

  async subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void> {
    try {
      await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
      this.startLoop(this.poll(streams, group, consumerName, handler));
      this.startLoop(this.recover(streams, group, consumerName, handler));
    } catch (error) {
      throw new SubscribeFailed("Failed to subscribe to event streams", { cause: error });
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    this.redis.disconnect();
  }

  private startLoop(loop: Promise<void>): void {
    this.backgroundLoops.add(loop);
    loop.catch((error) => {
      if (!this.stopped) {
        this.stopped = true;
        this.onLoopFailure(new SubscribeFailed("Redis subscriber background loop failed", { cause: error }));
      }
    }).finally(() => {
      this.backgroundLoops.delete(loop);
    });
  }

  private async createGroup(stream: string, group: string): Promise<void> {
    try {
      await this.redis.xgroup("CREATE", stream, group, "$", "MKSTREAM");
    } catch (error) {
      if (!(error instanceof Error) || !error.message.includes("BUSYGROUP")) {
        throw error;
      }
    }
  }

  private async poll(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void> {
    while (!this.stopped) {
      const messages = (await (this.redis as unknown as { xreadgroup: (...args: unknown[]) => Promise<unknown> }).xreadgroup(
        "GROUP",
        group,
        consumerName,
        "BLOCK",
        2000,
        "COUNT",
        10,
        "STREAMS",
        ...streams,
        ...streams.map(() => ">"),
      )) as StreamMessages[] | null;
      if (!messages) {
        continue;
      }
      await this.processMessages(messages, group, handler);
    }
  }

  private async recover(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void> {
    while (!this.stopped) {
      await sleep(RECOVERY_MIN_IDLE_MS);
      for (const stream of streams) {
        const claimed = (await this.redis.xautoclaim(stream, group, consumerName, RECOVERY_MIN_IDLE_MS, "0", "COUNT", 100)) as unknown[];
        const entries = (Array.isArray(claimed[1]) ? claimed[1] : []) as StreamEntry[];
        for (const entry of entries) {
          const deliveries = await this.deliveryCount(stream, group, entry[0]);
          if (deliveries >= MAX_DELIVERY_ATTEMPTS) {
            await this.moveToDlq(stream, group, entry);
          } else {
            await this.processEntry(stream, group, entry, handler);
          }
        }
      }
    }
  }

  private async processMessages(
    messages: StreamMessages[],
    group: string,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void> {
    for (const [stream, entries] of messages) {
      for (const entry of entries) {
        await this.processEntry(stream, group, entry, handler);
      }
    }
  }

  private async processEntry(
    stream: string,
    group: string,
    entry: StreamEntry,
    handler: (envelope: EventEnvelope) => Promise<void>,
  ): Promise<void> {
    const envelopeJson = fieldValue(entry[1], "envelope");
    if (!envelopeJson) {
      await this.moveToDlq(stream, group, entry);
      return;
    }
    let envelope: EventEnvelope;
    try {
      envelope = JSON.parse(envelopeJson) as EventEnvelope;
    } catch {
      await this.moveToDlq(stream, group, entry);
      return;
    }
    if (this.consumedEventIds.has(envelope.eventId)) {
      await this.redis.xack(stream, group, entry[0]);
      return;
    }
    try {
      await handler(envelope);
      this.consumedEventIds.add(envelope.eventId);
      await this.redis.xack(stream, group, entry[0]);
    } catch (error) {
      if (error instanceof HandlerError && error.kind === "fatal") {
        await this.moveToDlq(stream, group, entry);
      }
    }
  }

  private async moveToDlq(stream: string, group: string, entry: StreamEntry): Promise<void> {
    const envelopeJson = fieldValue(entry[1], "envelope") ?? JSON.stringify({});
    await this.redis.xadd(dlqForStream(stream), "MAXLEN", "~", STREAM_MAXLEN, "*", "envelope", envelopeJson);
    await this.redis.xack(stream, group, entry[0]);
  }

  private async deliveryCount(stream: string, group: string, entryId: string): Promise<number> {
    const pending = (await this.redis.xpending(stream, group, entryId, entryId, 1)) as unknown[];
    const first = pending[0];
    if (Array.isArray(first) && typeof first[3] === "number") {
      return first[3];
    }
    return 0;
  }
}

function fieldValue(fields: string[], name: string): string | undefined {
  for (let index = 0; index < fields.length; index += 2) {
    if (fields[index] === name) {
      return fields[index + 1];
    }
  }
  return undefined;
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, milliseconds);
    timer.unref?.();
  });
}

function defaultLoopFailureHandler(error: unknown): void {
  console.error(error);
  process.exit(1);
}
