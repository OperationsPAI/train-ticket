// ---------------------------------------------------------------------------
// Redis Streams adapter for EventSubscriber
// Implements the port defined in ../../ports/messaging.ts
// Lives ONLY under adapters/messaging/ — no Redis types outside this module.
// ---------------------------------------------------------------------------

import { type Redis } from "ioredis";

import { SubscribeFailed, type EventEnvelope, type EventHandler, type EventSubscriber } from "../../ports/messaging.js";

const POLL_TIMEOUT_MS = 2000;
const BATCH_SIZE = 10;
const CLAIM_INTERVAL_MS = 60000;
const MAX_DELIVERY_COUNT = 5;
const MIN_IDLE_MS = 60000;

export class RedisEventSubscriber implements EventSubscriber {
  private redis: Redis;
  private running = false;

  constructor(redis: Redis) {
    this.redis = redis;
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

    const result = await handler(envelope);

    switch (result) {
      case "ack":
        await (this.redis as any).xack(stream, group, entryId);
        break;
      case "dlq": {
        // Move to dead-letter stream
        const dlqStream = `${stream}:dlq`;
        await (this.redis as any).xadd(dlqStream, "MAXLEN", "~", "100000", "*", "envelope", JSON.stringify(envelope));
        await (this.redis as any).xack(stream, group, entryId);
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
    // XAUTOCLAIM recovers pending messages from other consumers
    const result: any = await (this.redis as any).xautoclaim(
      stream,
      group,
      consumerName,
      MIN_IDLE_MS,
      "0",
      "COUNT",
      100,
    );

    // result is [nextStartId, [entries]]
    const entries = result[1] as Array<[string, string[]]>;
    if (!entries || entries.length === 0) return;

    for (const entry of entries) {
      // Check delivery count from the raw entry info
      let deliveryCount = 1;
      try {
        const pendingInfo: any = await (this.redis as any).xpending(stream, group, "-", "+", 100);
        for (const pentry of pendingInfo) {
          if (pentry[0] === entry[0]) {
            deliveryCount = pentry[3]; // delivery count
            break;
          }
        }
      } catch {
        // ignore
      }

      if (deliveryCount >= MAX_DELIVERY_COUNT) {
        // Move to DLQ
        const dlqStream = `${stream}:dlq`;
        const fields = entry[1];
        const envelopeIdx = fields.indexOf("envelope");
        if (envelopeIdx !== -1 && envelopeIdx + 1 < fields.length) {
          await (this.redis as any).xadd(dlqStream, "MAXLEN", "~", "100000", "*", "envelope", fields[envelopeIdx + 1]);
        }
        await (this.redis as any).xack(stream, group, entry[0]);
      } else {
        // Process normally
        await this.processEntry(stream, group, entry, handler);
      }
    }
  }
}
