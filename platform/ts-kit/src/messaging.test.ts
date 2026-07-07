import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createEventEnvelope, HandlerError, RedisEventSubscriber } from "./messaging.js";
import { isPrefixedUuidV7, newCommandId, newCorrelationId, newEventId } from "./ids.js";

describe("EventEnvelope factory", () => {
  it("generates v7-prefixed envelope IDs when callers omit them", () => {
    const envelope = createEventEnvelope({
      eventType: "ExampleCreated",
      producer: "ts-kit-test",
      payload: {},
    });

    assert.equal(isPrefixedUuidV7(envelope.eventId, ["evt"]), true);
    assert.equal(isPrefixedUuidV7(envelope.correlationId, ["corr"]), true);
    assert.equal(isPrefixedUuidV7(envelope.causationId ?? "", ["cmd"]), true);
  });

  it("accepts caller-supplied v7-prefixed envelope IDs", () => {
    const eventId = newEventId();
    const correlationId = newCorrelationId();
    const causationId = newCommandId();

    const envelope = createEventEnvelope({
      eventId,
      correlationId,
      causationId,
      eventType: "ExampleCreated",
      producer: "ts-kit-test",
      payload: {},
    });

    assert.equal(envelope.eventId, eventId);
    assert.equal(envelope.correlationId, correlationId);
    assert.equal(envelope.causationId, causationId);
  });

  it("rejects v4 and garbage caller-supplied IDs", () => {
    assert.throws(
      () => createEventEnvelope({
        eventId: "evt-550e8400-e29b-41d4-a716-446655440000",
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        payload: {},
      }),
      /UUID v7/,
    );
    assert.throws(
      () => createEventEnvelope({
        correlationId: "corr-not-a-uuid",
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        payload: {},
      }),
      /UUID v7/,
    );
    assert.throws(
      () => createEventEnvelope({
        causationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
        eventType: "ExampleCreated",
        producer: "ts-kit-test",
        payload: {},
      }),
      /cmd- or evt-|cmd-/,
    );
  });
});


describe("RedisEventSubscriber DLQ observability", () => {
  it("adds attribution metadata and warns when dead-lettering fatal handler errors", async () => {
    const xadds: unknown[][] = [];
    const xacks: unknown[][] = [];
    const redis = {
      xadd: async (...args: unknown[]) => { xadds.push(args); return "1-1"; },
      xack: async (...args: unknown[]) => { xacks.push(args); return 1; },
    };
    const subscriber = new RedisEventSubscriber(redis as never);
    const envelope = createEventEnvelope({ eventType: "Poisoned", producer: "ts-kit-test", payload: {} });
    const entry: [string, string[]] = ["1-0", ["envelope", JSON.stringify(envelope)]];
    const warnings: unknown[] = [];
    const originalWarn = console.warn;
    console.warn = (value?: unknown) => { warnings.push(value); };
    try {
      await (subscriber as unknown as { processEntry: (stream: string, group: string, consumerName: string, entry: [string, string[]], handler: () => unknown) => Promise<void> })
        .processEntry("events:ts-kit-test", "ts-kit", "consumer-a", entry, () => { throw new HandlerError("fatal", "bad payload"); });
    } finally {
      console.warn = originalWarn;
    }

    const args = xadds[0];
    assert.equal(args[0], "events:ts-kit-test:dlq");
    assert.equal(args[5], "envelope");
    assert.equal(args[7], "consumerGroup");
    assert.equal(args[8], "ts-kit");
    assert.equal(args[9], "consumerName");
    assert.equal(args[10], "consumer-a");
    assert.equal(args[11], "failureReason");
    assert.equal(args[12], "HandlerError: bad payload");
    assert.equal(args[13], "attempts");
    assert.equal(args[14], "1");
    assert.equal(args[15], "deadLetteredAt");
    assert.equal(typeof args[16], "string");
    assert.equal(xacks.length, 1);
    assert.deepEqual(warnings[0], {
      service: "ts-kit",
      stream: "events:ts-kit-test",
      eventId: envelope.eventId,
      failureReason: "HandlerError: bad payload",
      message: "moving message to DLQ",
    });
  });
});
