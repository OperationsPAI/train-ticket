import assert from "node:assert/strict";
import crypto from "node:crypto";
import { describe, it } from "node:test";

import {
  InMemoryEventPublisher,
  InMemoryEventSubscriber,
} from "./adapters/messaging/in-memory.js";
import {
  PublishFailed,
  type EventEnvelope,
  type EventHandler,
} from "./ports/messaging.js";

function makeEnvelope(overrides: Partial<EventEnvelope> = {}): EventEnvelope {
  return {
    eventId: `evt-${crypto.randomUUID()}`,
    eventType: "OfferQuoted",
    schemaVersion: 1,
    producer: "offer-management",
    correlationId: `corr-${crypto.randomUUID()}`,
    occurredAt: new Date().toISOString(),
    payload: {},
    ...overrides,
  };
}

describe("EventPublisher port — InMemoryEventPublisher", () => {
  it("publishes an event and stores it in memory", async () => {
    const publisher = new InMemoryEventPublisher();
    const envelope = makeEnvelope();

    await publisher.publish(envelope);

    assert.equal(publisher.published.length, 1);
    assert.equal(publisher.published[0].eventId, envelope.eventId);
  });

  it("throws PublishFailed when failNext is set", async () => {
    const publisher = new InMemoryEventPublisher();
    publisher.failNext = true;

    await assert.rejects(
      () => publisher.publish(makeEnvelope()),
      PublishFailed,
    );
  });

  it("wraps events in a correct envelope with all required fields", async () => {
    const publisher = new InMemoryEventPublisher();
    const eventId = `evt-${crypto.randomUUID()}`;
    const correlationId = `corr-${crypto.randomUUID()}`;
    const envelope = makeEnvelope({
      eventId,
      eventType: "OfferQuoted",
      schemaVersion: 1,
      producer: "offer-management",
      correlationId,
      occurredAt: "2026-07-05T10:30:00.000Z",
      causationId: "cmd-123",
      payload: {
        offerId: "off-abc",
        offerVersion: 1,
        total: { currency: "CNY", minorUnits: 10400 },
      },
    });

    await publisher.publish(envelope);

    assert.equal(publisher.published.length, 1);
    const saved = publisher.published[0];
    assert.equal(saved.eventId, eventId);
    assert.equal(saved.eventType, "OfferQuoted");
    assert.equal(saved.schemaVersion, 1);
    assert.equal(saved.producer, "offer-management");
    assert.equal(saved.correlationId, correlationId);
    assert.equal(saved.causationId, "cmd-123");
    assert.equal(saved.occurredAt, "2026-07-05T10:30:00.000Z");
    assert.deepEqual(saved.payload, {
      offerId: "off-abc",
      offerVersion: 1,
      total: { currency: "CNY", minorUnits: 10400 },
    });
  });

  it("supports findByProducer and findByEventType convenience methods", async () => {
    const publisher = new InMemoryEventPublisher();

    await publisher.publish(makeEnvelope({ eventType: "OfferQuoted", producer: "offer-management" }));
    await publisher.publish(makeEnvelope({ eventType: "OfferExpired", producer: "offer-management" }));
    await publisher.publish(makeEnvelope({ eventType: "OfferQuoted", producer: "other-service" }));

    assert.equal(publisher.findByProducer("offer-management").length, 2);
    assert.equal(publisher.findByProducer("other-service").length, 1);
    assert.equal(publisher.findByEventType("OfferQuoted").length, 2);
    assert.equal(publisher.findByEventType("OfferExpired").length, 1);
  });
});

describe("EventSubscriber port — InMemoryEventSubscriber", () => {
  it("calls the handler with received events", async () => {
    const subscriber = new InMemoryEventSubscriber();
    const received: EventEnvelope[] = [];
    const handler: EventHandler = async (env) => {
      received.push(env);
      return "ack";
    };

    const signal = new AbortController().signal;
    await subscriber.subscribe(["events:test"], "test-group", "test-consumer", handler, signal);

    const envelope = makeEnvelope();
    const result = await subscriber.simulateEvent(envelope);

    assert.equal(result, "ack");
    assert.equal(received.length, 1);
    assert.equal(received[0].eventId, envelope.eventId);
  });

  it("deduplicates a duplicate eventId via handler returning ack for already-seen ids", async () => {
    const subscriber = new InMemoryEventSubscriber();
    const seen = new Set<string>();
    const handler: EventHandler = async (env) => {
      if (seen.has(env.eventId)) {
        return "ack"; // already processed, ack and skip
      }
      seen.add(env.eventId);
      return "ack";
    };

    const signal = new AbortController().signal;
    await subscriber.subscribe(["events:test"], "test-group", "test-consumer", handler, signal);

    const envelope = makeEnvelope();
    const result1 = await subscriber.simulateEvent(envelope);
    assert.equal(result1, "ack");
    assert.equal(seen.size, 1);

    // Same eventId again
    const result2 = await subscriber.simulateEvent(envelope);
    assert.equal(result2, "ack");
    assert.equal(seen.size, 1); // should not have added again
  });

  it("returns retry for transient errors and dlq for fatal errors", async () => {
    const subscriber = new InMemoryEventSubscriber();
    let callCount = 0;
    const handler: EventHandler = async () => {
      callCount++;
      if (callCount === 1) return "retry";
      if (callCount === 2) return "dlq";
      return "ack";
    };

    const signal = new AbortController().signal;
    await subscriber.subscribe(["events:test"], "test-group", "test-consumer", handler, signal);

    const envelope = makeEnvelope();

    assert.equal(await subscriber.simulateEvent(envelope), "retry");
    assert.equal(callCount, 1);

    assert.equal(await subscriber.simulateEvent(envelope), "dlq");
    assert.equal(callCount, 2);

    assert.equal(await subscriber.simulateEvent(envelope), "ack");
    assert.equal(callCount, 3);
  });
});
