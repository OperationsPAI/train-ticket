import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createEventEnvelope } from "./messaging.js";
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
