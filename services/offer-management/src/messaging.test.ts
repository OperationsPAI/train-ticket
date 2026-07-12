import assert from "node:assert/strict";
import crypto from "node:crypto";
import { describe, it } from "node:test";

import {
  InMemoryEventPublisher,
  InMemoryEventSubscriber,
} from "./adapters/messaging/in-memory.js";
import { RedisEventSubscriber } from "./adapters/messaging/subscriber.js";
import { dlqStreamKey, streamKey } from "./adapters/messaging/stream-config.js";
import {
  PublishFailed,
  type EventEnvelope,
  type EventHandler,
} from "./ports/messaging.js";

function makeEnvelope(overrides: Partial<EventEnvelope> = {}): EventEnvelope {
  return {
    eventId: `evt-${uuidV7()}`,
    eventType: "OfferQuoted",
    schemaVersion: 1,
    producer: "offer-management",
    correlationId: `corr-${uuidV7()}`,
    occurredAt: new Date().toISOString(),
    payload: {},
    ...overrides,
  };
}

function uuidV7(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  const timestamp = BigInt(Date.now());

  bytes[0] = Number((timestamp >> 40n) & 0xffn);
  bytes[1] = Number((timestamp >> 32n) & 0xffn);
  bytes[2] = Number((timestamp >> 24n) & 0xffn);
  bytes[3] = Number((timestamp >> 16n) & 0xffn);
  bytes[4] = Number((timestamp >> 8n) & 0xffn);
  bytes[5] = Number(timestamp & 0xffn);
  bytes[6] = (bytes[6] & 0x0f) | 0x70;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;

  const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
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
    const eventId = `evt-${uuidV7()}`;
    const correlationId = `corr-${uuidV7()}`;
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

  it("serializes OfferQuoted payload using exactly the contract fields", async () => {
    const publisher = new InMemoryEventPublisher();
    const payload = {
      offerId: `off-${uuidV7()}`,
      offerVersion: 1,
      quoteRequestId: "quote-1",
      accountId: "account-1",
      channelId: "web",
      itineraryId: "itin-456",
      itineraryVersion: "itinerary-v1",
      travelerSetHash: "tvl-001,tvl-002",
      availabilitySnapshotRefs: ["availability-1"],
      priceSnapshotRef: "price-1",
      fareQuoteRefs: ["fq-1"],
      ruleSnapshotRefs: ["rule-1"],
      total: { currency: "CNY", minorUnits: 20000 },
      expiresAt: "2026-07-05T10:30:00.000Z",
      priceGuaranteeLevel: "FIXED_UNTIL_EXPIRY",
      downstreamReference: {
        offerId: `off-${uuidV7()}`,
        offerVersion: 1,
        priceSnapshotRef: "price-1",
        ruleSnapshotRef: "rule-1",
      },
    };

    await publisher.publish(makeEnvelope({ eventType: "OfferQuoted", payload }));

    assert.deepEqual(Object.keys(publisher.published[0].payload).sort(), [
      "accountId",
      "availabilitySnapshotRefs",
      "channelId",
      "downstreamReference",
      "expiresAt",
      "fareQuoteRefs",
      "itineraryId",
      "itineraryVersion",
      "offerId",
      "offerVersion",
      "priceGuaranteeLevel",
      "priceSnapshotRef",
      "quoteRequestId",
      "ruleSnapshotRefs",
      "total",
      "travelerSetHash",
    ].sort());
    assert.equal(Object.hasOwn(publisher.published[0].payload, "boundaryProof"), false);
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
      return "ack" as const;
    };

    const signal = new AbortController().signal;
    await subscriber.subscribe(["test-stream"], "test-group", "test-consumer", handler, signal);

    const envelope = makeEnvelope();
    const result = await subscriber.simulateEvent(envelope);

    assert.equal(result, "ack");
    assert.equal(received.length, 1);
    assert.equal(received[0].eventId, envelope.eventId);
  });

  it("invokes handleBatch once for a Redis read batch and acks each processed entry", async () => {
    const first = makeEnvelope({ eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222" });
    const second = makeEnvelope({ eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c223" });
    const redis = new FakeRedisForBatch([
      ["5-0", ["envelope", JSON.stringify(first)]],
      ["6-0", ["envelope", JSON.stringify(second)]],
    ]);
    const subscriber = new RedisEventSubscriber(redis as never);
    const batches: (readonly EventEnvelope[])[] = [];
    const handler = (async () => "dlq") as EventHandler;
    handler.handleBatch = async (envelopes) => {
      batches.push(envelopes);
      return "ack" as const;
    };

    await (subscriber as unknown as { processMessages: (messages: unknown, group: string, consumer: string, handler: EventHandler) => Promise<void> })
      .processMessages([[streamKey("fare-pricing"), redis.entries]], "offer-management", "offer-management-test", handler);

    assert.equal(batches.length, 1);
    assert.deepEqual(batches[0].map(({ eventId }) => eventId), [first.eventId, second.eventId]);
    assert.deepEqual(redis.xackCalls, [
      [streamKey("fare-pricing"), "offer-management", "5-0"],
      [streamKey("fare-pricing"), "offer-management", "6-0"],
    ]);
    assert.equal(redis.xaddCalls.length, 0);
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
    await subscriber.subscribe(["test-stream"], "test-group", "test-consumer", handler, signal);

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
    await subscriber.subscribe(["test-stream"], "test-group", "test-consumer", handler, signal);

    const envelope = makeEnvelope();

    assert.equal(await subscriber.simulateEvent(envelope), "retry");
    assert.equal(callCount, 1);

    assert.equal(await subscriber.simulateEvent(envelope), "dlq");
    assert.equal(callCount, 2);

    assert.equal(await subscriber.simulateEvent(envelope), "ack");
    assert.equal(callCount, 3);
  });
});


describe("RedisEventSubscriber recovery", () => {
  it("uses Redis pending delivery counts for XAUTOCLAIM recovery DLQ decisions", async () => {
    const envelope = makeEnvelope({ eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222" });
    const redis = new FakeRedisForRecovery("5-0", JSON.stringify(envelope), 5);
    const subscriber = new RedisEventSubscriber(redis as never);

    const sourceStream = streamKey("fare-pricing");
    const group = "offer-management";

    await (subscriber as unknown as { claimAndProcess: (stream: string, group: string, consumer: string, handler: EventHandler) => Promise<void> })
      .claimAndProcess(sourceStream, group, "offer-management-test", async () => {
        throw new Error("handler should not run for poison message");
      });

    assert.deepEqual(redis.xpendingCalls, [[sourceStream, group, "5-0", "5-0", 1]]);
    assert.equal(redis.xaddCalls.length, 1);
    assert.equal(redis.xaddCalls[0][0], dlqStreamKey("fare-pricing"));
    assert.deepEqual(redis.xackCalls, [[sourceStream, group, "5-0"]]);
  });
});


class FakeRedisForBatch {
  readonly xaddCalls: unknown[][] = [];
  readonly xackCalls: unknown[][] = [];

  constructor(readonly entries: [string, string[]][]) {}

  async xadd(...args: unknown[]): Promise<string> {
    this.xaddCalls.push(args);
    return "7-0";
  }

  async xack(...args: unknown[]): Promise<number> {
    this.xackCalls.push(args);
    return 1;
  }
}

class FakeRedisForRecovery {
  readonly xaddCalls: unknown[][] = [];
  readonly xackCalls: unknown[][] = [];
  readonly xpendingCalls: unknown[][] = [];

  constructor(
    private readonly entryId: string,
    private readonly envelopeJson: string,
    private readonly deliveryCount: number,
  ) {}

  async xautoclaim(): Promise<unknown[]> {
    return ["0-0", [[this.entryId, ["envelope", this.envelopeJson]]]];
  }

  async xpending(...args: unknown[]): Promise<unknown[]> {
    this.xpendingCalls.push(args);
    return [[this.entryId, "offer-management-test", 60000, this.deliveryCount]];
  }

  async xadd(...args: unknown[]): Promise<string> {
    this.xaddCalls.push(args);
    return "6-0";
  }

  async xack(...args: unknown[]): Promise<number> {
    this.xackCalls.push(args);
    return 1;
  }
}
