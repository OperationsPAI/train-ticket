import { Redis } from "ioredis";

import {
  type EventEnvelope,
  type EventHandler,
  type EventHandlerResult,
  type EventSubscriber,
  SubscribeFailed,
} from "../../application/messaging.js";
import { dlqStream, redisUrl } from "./stream-config.js";

const READ_BLOCK_MS = 2_000;
const READ_COUNT = 10;
const CLAIM_MIN_IDLE_MS = 60_000;
const MAX_DELIVERIES = 5;
const RETENTION_MAXLEN = 100_000;

type RedisStreamReadResponse = Array<[string, Array<[string, string[]]>]> | null;
type AutoClaimMessage = [string, string[]];

export class RedisStreamEventSubscriber implements EventSubscriber {
  private stopped = false;
  private pollLoop?: Promise<void>;
  private recoveryLoop?: Promise<void>;

  constructor(private readonly redis = new Redis(redisUrl(), { lazyConnect: true })) {}

  async subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
  ): Promise<void> {
    try {
      await this.ensureConnected();
      await Promise.all(streams.map((stream) => this.createGroup(stream, group)));
      this.pollLoop = this.poll(streams, group, consumerName, handler);
      this.recoveryLoop = this.recover(streams, group, consumerName, handler);
    } catch (error) {
      throw new SubscribeFailed("Failed to subscribe to notification event streams", { cause: error });
    }
  }

  async stop(): Promise<void> {
    this.stopped = true;
    await Promise.allSettled([this.pollLoop, this.recoveryLoop].filter((loop): loop is Promise<void> => loop !== undefined));
    this.redis.disconnect();
  }

  private async poll(streams: readonly string[], group: string, consumerName: string, handler: EventHandler): Promise<void> {
    while (!this.stopped) {
      const response = await this.redis.call("XREADGROUP",
        "GROUP",
        group,
        consumerName,
        "BLOCK",
        READ_BLOCK_MS,
        "COUNT",
        READ_COUNT,
        "STREAMS",
        ...streams,
        ...streams.map(() => ">"),
      ) as RedisStreamReadResponse;

      await this.handleReadResponse(response, group, handler);
    }
  }

  private async recover(streams: readonly string[], group: string, consumerName: string, handler: EventHandler): Promise<void> {
    while (!this.stopped) {
      await sleep(CLAIM_MIN_IDLE_MS);
      for (const stream of streams) {
        const claimed = await this.redis.xautoclaim(stream, group, consumerName, CLAIM_MIN_IDLE_MS, "0", "COUNT", READ_COUNT) as unknown[];
        const messages = Array.isArray(claimed[1]) ? claimed[1] as AutoClaimMessage[] : [];
        for (const [entryId, fields] of messages) {
          const deliveries = await this.deliveryCount(stream, group, entryId);
          if (deliveries >= MAX_DELIVERIES) {
            await this.moveToDlq(stream, fields);
            await this.redis.xack(stream, group, entryId);
            continue;
          }
          await this.handleMessage(stream, entryId, fields, group, handler);
        }
      }
    }
  }

  private async handleReadResponse(response: RedisStreamReadResponse, group: string, handler: EventHandler): Promise<void> {
    if (response === null) {
      return;
    }

    for (const [stream, messages] of response) {
      for (const [entryId, fields] of messages) {
        await this.handleMessage(stream, entryId, fields, group, handler);
      }
    }
  }

  private async handleMessage(
    stream: string,
    entryId: string,
    fields: string[],
    group: string,
    handler: EventHandler,
  ): Promise<void> {
    const serialized = envelopeField(fields);
    if (serialized === undefined) {
      await this.redis.xack(stream, group, entryId);
      return;
    }

    let envelope: EventEnvelope;
    try {
      envelope = JSON.parse(serialized) as EventEnvelope;
    } catch {
      await this.moveToDlq(stream, fields);
      await this.redis.xack(stream, group, entryId);
      return;
    }

    const result = await handler(envelope);
    await this.applyResult(stream, entryId, fields, group, result);
  }

  private async applyResult(
    stream: string,
    entryId: string,
    fields: string[],
    group: string,
    result: EventHandlerResult,
  ): Promise<void> {
    if (result.ok) {
      await this.redis.xack(stream, group, entryId);
      return;
    }
    if (result.kind === "fatal") {
      await this.moveToDlq(stream, fields);
      await this.redis.xack(stream, group, entryId);
    }
  }

  private async moveToDlq(stream: string, fields: string[]): Promise<void> {
    const serialized = envelopeField(fields) ?? "{}";
    await this.redis.xadd(dlqStream(stream), "MAXLEN", "~", RETENTION_MAXLEN, "*", "envelope", serialized);
  }

  private async deliveryCount(stream: string, group: string, entryId: string): Promise<number> {
    const pending = await this.redis.xpending(stream, group, entryId, entryId, 1) as unknown[];
    const entry = pending[0];
    return Array.isArray(entry) && typeof entry[3] === "number" ? entry[3] : 1;
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

  private async ensureConnected(): Promise<void> {
    if (this.redis.status === "wait") {
      await this.redis.connect();
    }
  }
}

function envelopeField(fields: string[]): string | undefined {
  for (let index = 0; index < fields.length; index += 2) {
    if (fields[index] === "envelope") {
      return fields[index + 1];
    }
  }
  return undefined;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
