import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { setTimeout as delay } from "node:timers/promises";

import { ACCOUNT_STREAM } from "./adapters/messaging/stream-config.js";
import { RedisStreamEventSubscriber } from "./adapters/messaging/subscriber.js";
import { UserAccount } from "./domain.js";
import { isPrefixedUuidV7 } from "@trainticket/ts-kit";
import { toEventEnvelope, type EventEnvelope, type HandlerResult } from "./ports.js";

class FakeRedisForSubscriber {
  readonly acked: Array<{ stream: string; group: string; entryId: string }> = [];
  readonly dlqEnvelopes: string[] = [];
  private readonly reads: unknown[] = [];

  constructor(reads: unknown[]) {
    this.reads = [...reads];
  }

  async xgroup(): Promise<void> {}

  async call(command: string): Promise<unknown> {
    // The subscriber also issues XINFO CONSUMERS (dead-consumer pruning).
    // Only XREADGROUP may consume a queued read; otherwise the first event
    // is silently swallowed by the prune call.
    if (command !== "XREADGROUP") {
      return [];
    }
    const next = this.reads.shift();
    if (next instanceof Error) {
      throw next;
    }
    await delay(1);
    return next ?? null;
  }

  async xack(stream: string, group: string, entryId: string): Promise<void> {
    this.acked.push({ stream, group, entryId });
  }

  async xadd(stream: string, _maxlen: string, _approximate: string, _length: number, _id: string, _field: string, envelope: string): Promise<void> {
    this.dlqEnvelopes.push(`${stream}:${envelope}`);
  }

  async xautoclaim(): Promise<[string, unknown[]]> {
    return ["0-0", []];
  }

  disconnect(): void {}
}

class FakeSubscriber {
  private readonly seen = new Set<string>();
  handled = 0;

  async deliver(envelope: EventEnvelope, handler: (envelope: EventEnvelope) => HandlerResult): Promise<void> {
    if (this.seen.has(envelope.eventId)) {
      return;
    }
    const result = handler(envelope);
    if (result.ok) {
      this.seen.add(envelope.eventId);
      this.handled += 1;
    }
  }
}

describe("account event ports", () => {
  it("wraps account domain events in the canonical event envelope", () => {
    const { event } = UserAccount.create({ accountId: "acct_123", correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" });

    const envelope = toEventEnvelope(event, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222");

    assert.equal(isPrefixedUuidV7(envelope.eventId, ["evt"]), true);
    assert.equal(envelope.eventType, "AccountCreated");
    assert.equal(envelope.schemaVersion, 1);
    assert.equal(envelope.producer, "account");
    assert.equal(envelope.causationId, "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.equal(isPrefixedUuidV7(envelope.causationId, ["cmd"]), true);
    assert.equal(envelope.correlationId, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.equal(isPrefixedUuidV7(envelope.correlationId, ["corr"]), true);
    assert.match(envelope.occurredAt, /^\d{4}-\d{2}-\d{2}T.*Z$/);
    assert.deepEqual(envelope.payload, {
      accountId: "acct_123",
      occurredAt: envelope.occurredAt,
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    });
    assert.deepEqual(Object.keys(envelope), [
      "eventId",
      "eventType",
      "schemaVersion",
      "producer",
      "causationId",
      "correlationId",
      "occurredAt",
      "payload",
    ]);
  });

  it("deduplicates consumed events by eventId", async () => {
    const subscriber = new FakeSubscriber();
    const envelope = toEventEnvelope(UserAccount.create({ accountId: "acct_dup" }).event, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    const seen: string[] = [];

    await subscriber.deliver(envelope, (message) => {
      seen.push(message.eventId);
      return { ok: true };
    });
    await subscriber.deliver(envelope, (message) => {
      seen.push(message.eventId);
      return { ok: true };
    });

    assert.deepEqual(seen, [envelope.eventId]);
    assert.equal(subscriber.handled, 1);
  });

  it("continues polling after handler throws and leaves the message pending for retry", async () => {
    const firstEnvelope = toEventEnvelope(UserAccount.create({ accountId: "acct_throw_1" }).event, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    const secondEnvelope = toEventEnvelope(UserAccount.create({ accountId: "acct_throw_2" }).event, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222", "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    const redis = new FakeRedisForSubscriber([
      [[ACCOUNT_STREAM, [["1-0", ["envelope", JSON.stringify(firstEnvelope)]]]]],
      [[ACCOUNT_STREAM, [["2-0", ["envelope", JSON.stringify(secondEnvelope)]]]]],
    ]);
    const subscriber = new RedisStreamEventSubscriber(redis as never);
    const handled: string[] = [];
    const loggedErrors: unknown[] = [];
    const originalConsoleError = console.error;
    console.error = (...args: unknown[]) => {
      loggedErrors.push(args);
    };

    try {
      await subscriber.subscribe([ACCOUNT_STREAM], "account", "account-test", (envelope) => {
        handled.push(envelope.eventId);
        if (envelope.eventId === firstEnvelope.eventId) {
          throw new Error("handler exploded");
        }
        return { ok: true };
      });

      for (let index = 0; index < 20 && redis.acked.length < 2; index += 1) {
        await delay(10);
      }
    } finally {
      await subscriber.close();
      console.error = originalConsoleError;
    }

    // A thrown error is a bounded retry, not a dead letter: a database that
    // refuses connections is evidence about the handler, not about the event.
    // So the throwing message stays pending, the poll carries on to the next
    // one, and nothing reaches the dead letter until the handler has failed
    // MAX_HANDLER_FAILURES times. The retry is logged as a warning, which is why
    // no console.error is recorded here.
    assert.equal(loggedErrors.length, 0);
    assert.deepEqual(handled, [firstEnvelope.eventId, secondEnvelope.eventId]);
    assert.deepEqual(redis.acked.map((ack) => ack.entryId), ["2-0"]);
    assert.equal(redis.dlqEnvelopes.length, 0);
  });
});
