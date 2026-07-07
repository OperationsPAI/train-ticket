import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { RedisEventSubscriber, createEventEnvelope } from "./messaging.js";
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
  it("writes attribution metadata and emits a warning when dead-lettering", async () => {
    const envelope = createEventEnvelope({ eventType: "PaymentCaptured", producer: "payment", payload: { id: "1" } });
    const xaddCalls: unknown[][] = [];
    const warnings: unknown[][] = [];
    const redis = {
      xackCalls: [] as unknown[][],
      async xadd(...args: unknown[]) {
        xaddCalls.push(args);
      },
      async xack(...args: unknown[]) {
        this.xackCalls.push(args);
      },
    };
    const subscriber = new RedisEventSubscriber(redis as any);
    const originalWarn = console.warn;
    console.warn = (...args: unknown[]) => warnings.push(args);
    try {
      await (subscriber as any).processEntry(
        "events:payment",
        "journey-order",
        "consumer-1",
        ["1-0", ["envelope", JSON.stringify(envelope)]],
        () => "dlq",
      );
    } finally {
      console.warn = originalWarn;
    }

    assert.equal(xaddCalls.length, 1);
    const fields = xaddCalls[0].slice(6);
    const valueFor = (name: string) => fields[fields.indexOf(name) + 1];
    assert.equal(valueFor("consumerGroup"), "journey-order");
    assert.equal(valueFor("consumerName"), "consumer-1");
    assert.equal(valueFor("failureReason"), "handler requested dead letter");
    assert.equal(valueFor("attempts"), "1");
    assert.match(String(valueFor("deadLetteredAt")), /Z$/);
    assert.match(String(warnings[0][0]), /dead-lettered event service=journey-order stream=events:payment/);
  });
});
