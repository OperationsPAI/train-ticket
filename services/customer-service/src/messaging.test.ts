import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { SupportCase } from "./domain.js";
import { ConsumedEventDeduplicator, InMemoryEventSubscriber, toEventEnvelope, type EventEnvelope } from "./application/messaging.js";

describe("customer-service messaging ports", () => {
  it("wraps domain events in the contract envelope", () => {
    const { event } = SupportCase.open({
      caseId: "sc-test-envelope",
      requesterRef: "tvl-test-envelope",
      channel: "WEB",
      priority: "NORMAL",
      description: "Need help",
      correlationId: "corr-test-envelope",
      causationId: "cmd-test-envelope",
      openedAt: new Date("2026-07-05T10:30:00.000Z"),
    });

    const envelope = toEventEnvelope(event);

    assert.deepEqual(Object.keys(envelope).sort(), [
      "causationId",
      "correlationId",
      "eventId",
      "eventType",
      "occurredAt",
      "payload",
      "producer",
      "schemaVersion",
    ].sort());
    assert.match(envelope.eventId, /^evt-/);
    assert.equal(envelope.eventType, "SupportCaseOpened");
    assert.equal(envelope.occurredAt, "2026-07-05T10:30:00.000Z");
    assert.match(envelope.correlationId, /^corr-/);
    assert.match(envelope.causationId, /^cmd-/);
    assert.equal(envelope.producer, "customer-service");
    assert.equal(envelope.schemaVersion, 1);
    assert.equal(envelope.payload.caseId, "sc-test-envelope");
  });

  it("deduplicates duplicate eventIds before handling", async () => {
    const deduplicator = new ConsumedEventDeduplicator();
    const envelope: EventEnvelope = {
      eventId: "evt-duplicate",
      eventType: "JourneyOrderCreated",
      occurredAt: "2026-07-05T10:30:00.000Z",
      correlationId: "corr-duplicate",
      causationId: "evt-upstream",
      producer: "journey-order",
      schemaVersion: 1,
      payload: { journeyOrderId: "ord-1" },
    };
    let handled = 0;

    assert.equal(await deduplicator.handle(envelope, async () => { handled += 1; }), true);
    assert.equal(await deduplicator.handle(envelope, async () => { handled += 1; }), false);
    assert.equal(handled, 1);
  });

  it("in-memory subscriber applies consumer-side deduplication", async () => {
    const subscriber = new InMemoryEventSubscriber();
    const envelope: EventEnvelope = {
      eventId: "evt-subscriber-duplicate",
      eventType: "JourneyOrderConfirmed",
      occurredAt: "2026-07-05T10:31:00.000Z",
      correlationId: "corr-subscriber-duplicate",
      causationId: "evt-upstream",
      producer: "journey-order",
      schemaVersion: 1,
      payload: {},
    };
    let handled = 0;
    await subscriber.subscribe(["journey-order-test-stream"], "customer-service", "customer-service-test", async () => {
      handled += 1;
    });

    assert.equal(await subscriber.emit(envelope), true);
    assert.equal(await subscriber.emit(envelope), false);
    assert.equal(handled, 1);
  });
});
