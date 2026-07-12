import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { RedisStreamEventSubscriber } from "./adapters/messaging/subscriber.js";
import { type EventEnvelope, successfulHandling } from "./index.js";

class FakeRedisForSubscriber {
  status = "ready";
  public readonly acked: string[] = [];
  private readCalls = 0;
  private readonly messages: Array<[string, string[]]>;

  constructor(envelopes: EventEnvelope[]) {
    this.messages = envelopes.map((envelope, index) => [`${index + 1}-0`, ["envelope", JSON.stringify(envelope)]]);
  }

  async connect(): Promise<void> {}

  disconnect(): void {}

  async xgroup(): Promise<void> {}

  async xautoclaim(): Promise<unknown[]> {
    return ["0-0", []];
  }

  async xadd(): Promise<void> {}

  async xpending(): Promise<unknown[]> {
    return [];
  }

  async xack(_stream: string, _group: string, entryId: string): Promise<void> {
    this.acked.push(entryId);
  }

  async call(command: string): Promise<unknown> {
    assert.equal(command, "XREADGROUP");
    this.readCalls += 1;
    if (this.readCalls <= this.messages.length) {
      return [["events:payment", [this.messages[this.readCalls - 1]]]];
    }
    throw new Error("stop test loop");
  }
}

function envelope(eventId: string): EventEnvelope {
  return {
    eventId,
    eventType: "PaymentCaptured",
    schemaVersion: 1,
    producer: "payment",
    causationId: "cmd-test",
    correlationId: "corr-test",
    occurredAt: "2026-07-05T10:00:00.000Z",
    payload: {
      recipientRef: "usr-test-001",
      paymentIntentId: "pi-test-001",
      businessRef: "ord-test-001",
      capturedAmount: { currency: "CNY", minorUnits: 35000 },
      channel: "wechat_pay",
      channelTransactionId: "wx-test-001",
    },
  };
}

describe("Redis stream subscriber resilience", () => {
  it("continues consuming when a handler throws during one iteration", async () => {
    const redis = new FakeRedisForSubscriber([envelope("evt-first"), envelope("evt-second")]);
    const subscriber = new RedisStreamEventSubscriber(redis as never);
    const seen: string[] = [];

    await subscriber.subscribe(["events:payment"], "notification", "notification-test", (received) => {
      seen.push(received.eventId);
      if (received.eventId === "evt-first") {
        throw new Error("transient handler failure");
      }
      void subscriber.stop();
      return successfulHandling();
    });

    await waitFor(() => redis.acked.includes("2-0"));

    assert.deepEqual(seen, ["evt-first", "evt-second"]);
    assert.deepEqual(redis.acked, ["2-0"]);
  });
});

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 1_000;
  while (Date.now() < deadline) {
    if (predicate()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  assert.fail("condition was not met before timeout");
}
