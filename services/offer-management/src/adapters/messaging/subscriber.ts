// ---------------------------------------------------------------------------
// Redis Streams adapter for EventSubscriber
// Implements the port defined in ../../ports/messaging.ts
// Lives ONLY under adapters/messaging/ — no Redis types outside this module.
// ---------------------------------------------------------------------------

import { type Redis } from "ioredis";

import { SubscribeFailed, type EventEnvelope, type EventHandler, type EventSubscriber } from "../../ports/messaging.js";
import { dlqStreamKey } from "./stream-config.js";

const POLL_TIMEOUT_MS = 2000;
const BATCH_SIZE = 10;
const CLAIM_INTERVAL_MS = 60000;
const MAX_DELIVERY_COUNT = 5;
const MIN_IDLE_MS = 60000;

export class RedisEventSubscriber implements EventSubscriber {
  private redis: Redis;
  private running = false;
  private readonly processedEventIds = new Set<string>();
  private startedPromise: Promise<void>;
  private resolveStarted!: () => void;
  private rejectStarted!: (error: unknown) => void;

  constructor(redis: Redis) {
    this.redis = redis;
    this.startedPromise = new Promise((resolve, reject) => {
      this.resolveStarted = resolve;
      this.rejectStarted = reject;
    });
  }

  started(): Promise<void> {
    return this.startedPromise;
  }

  async subscribe(
    streams: readonly string[],
    group: string,
    consumerName: string,
    handler: EventHandler,
    signal: AbortSignal,
  ): Promise<void> {
    this.running = true;

    // Create consumer groups (ignore BUSYGROUP errors)
    try {
      for (const stream of streams) {
        try {
          await (this.redis as any).xgroup("CREATE", stream, group, "$", "MKSTREAM");
        } catch (err: any) {
          // BUSYGROUP means the group already exists — that's fine
          if (!String(err).includes("BUSYGROUP")) {
            throw new SubscribeFailed(`Failed to create consumer group ${group} on ${stream}`, err);
          }
        }
      }
      this.resolveStarted();
    } catch (error) {
      this.running = false;
      this.rejectStarted(error);
      throw error;
    }

    // Start background recovery loop
    const recoveryTimer = setInterval(async () => {
      if (!this.running) return;
      for (const stream of streams) {
        try {
          await this.claimAndProcess(stream, group, consumerName, handler);
        } catch {
          // log and continue
        }
      }
    }, CLAIM_INTERVAL_MS);

    // Main polling loop
    try {
      while (this.running && !signal.aborted) {
        for (const stream of streams) {
          if (!this.running || signal.aborted) break;
          try {
            await this.pollStream(stream, group, consumerName, handler);
          } catch {
            // log and continue
          }
        }
      }
    } finally {
      clearInterval(recoveryTimer);
    }
  }

  stop(): void {
    this.running = false;
  }

  private async pollStream(
    stream: string,
    group: string,
    consumerName: string,
    handler: EventHandler,
  ): Promise<void> {
    const results: any = await (this.redis as any).xreadgroup(
      "GROUP",
      group,
      consumerName,
      "BLOCK",
      POLL_TIMEOUT_MS,
      "COUNT",
      BATCH_SIZE,
      "STREAMS",
      stream,
      ">",
    );

    if (!results) return;

    for (const [, entries] of results) {
      for (const entry of entries) {
        await this.processEntry(stream, group, entry, handler);
      }
    }
  }

  private async processEntry(
    stream: string,
    group: string,
    entry: [string, string[]],
    handler: EventHandler,
  ): Promise<void> {
    const [entryId, fields] = entry;

    // Find the envelope field
    const envelopeIdx = fields.indexOf("envelope");
    if (envelopeIdx === -1 || envelopeIdx + 1 >= fields.length) {
      // Malformed entry — ack and skip
      await (this.redis as any).xack(stream, group, entryId);
      return;
    }

    let envelope: EventEnvelope;
    try {
      envelope = JSON.parse(fields[envelopeIdx + 1]) as EventEnvelope;
    } catch {
      // Invalid JSON — ack and skip
      await (this.redis as any).xack(stream, group, entryId);
      return;
    }

    if (this.processedEventIds.has(envelope.eventId)) {
      await (this.redis as any).xack(stream, group, entryId);
      return;
    }

    const result = await handler(envelope);

    switch (result) {
      case "ack":
        this.processedEventIds.add(envelope.eventId);
        await (this.redis as any).xack(stream, group, entryId);
        break;
      case "dlq": {
        await this.moveToDlq(stream, group, [entryId, fields], JSON.stringify(envelope));
        break;
      }
      case "retry":
        // Leave in PEL for retry via XAUTOCLAIM
        break;
    }
  }

  private async claimAndProcess(
    stream: string,
    group: string,
    consumerName: string,
    handler: EventHandler,
  ): Promise<void> {
    const result: any = await (this.redis as any).xautoclaim(
      stream,
      group,
      consumerName,
      MIN_IDLE_MS,
      "0",
      "COUNT",
      100,
    );

    const entries = result[1] as Array<[string, string[]]>;
    if (!entries || entries.length === 0) return;

    const deliveryCounts = await this.deliveryCounts(stream, group, entries.map(([entryId]) => entryId));
    for (const [entryId, fields] of entries) {
      if ((deliveryCounts.get(entryId) ?? 1) >= MAX_DELIVERY_COUNT) {
        await this.moveToDlq(stream, group, [entryId, fields]);
        continue;
      }

      await this.processEntry(stream, group, [entryId, fields], handler);
    }
  }

  private async deliveryCounts(stream: string, group: string, entryIds: readonly string[]): Promise<Map<string, number>> {
    if (entryIds.length === 0) {
      return new Map();
    }

    const counts = new Map<string, number>();
    await Promise.all(entryIds.map(async (entryId) => {
      const pending: any = await (this.redis as any).xpending(stream, group, entryId, entryId, 1);
      const row = Array.isArray(pending) ? pending[0] : undefined;
      if (Array.isArray(row) && row[0] === entryId) {
        const deliveries = Number(row[3]);
        if (Number.isFinite(deliveries)) {
          counts.set(entryId, deliveries);
        }
      }
    }));
    return counts;
  }

  private async moveToDlq(
    stream: string,
    group: string,
    entry: [string, string[]],
    envelopeJson?: string,
  ): Promise<void> {
    const [entryId, fields] = entry;
    const envelopeIdx = fields.indexOf("envelope");
    const serializedEnvelope = envelopeJson ?? (envelopeIdx !== -1 ? fields[envelopeIdx + 1] : undefined);

    if (serializedEnvelope) {
      await (this.redis as any).xadd(
        dlqStreamKey(producerFromStream(stream)),
        "MAXLEN",
        "~",
        "100000",
        "*",
        "envelope",
        serializedEnvelope,
      );
    }

    await (this.redis as any).xack(stream, group, entryId);
  }

}

function producerFromStream(stream: string): string {
  return stream.startsWith("events:") ? stream.slice("events:".length).replace(/:dlq$/, "") : stream;
}
