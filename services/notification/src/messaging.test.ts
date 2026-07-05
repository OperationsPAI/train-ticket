import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  DeduplicatingEventHandler,
  InMemoryEventPublisher,
  NotificationApplicationService,
  NotificationTask,
  successfulHandling,
  toEventEnvelope,
  type EventEnvelope,
} from "./index.js";

const domainEvent = NotificationTask.schedule({
  notificationTaskId: "nt-test-001",
  triggerEventId: "evt-trigger-001",
  triggerEventType: "JourneyOrderConfirmed",
  correlationId: "corr-test-001",
  causationId: "cmd-test-001",
  recipientRef: "tvl-test-001",
  templateCode: "order_confirmed",
  channel: "EMAIL",
  intent: "ORDER_CONFIRMED",
  transactionRequired: true,
  variables: { orderId: "ord-001" },
  scheduledAt: new Date("2026-07-05T10:30:00.000Z"),
}).event;

describe("notification messaging integration surface", () => {
  it("wraps notification domain events in the contract event envelope", () => {
    const envelope = toEventEnvelope(domainEvent);

    assert.equal(envelope.eventId, domainEvent.eventId);
    assert.equal(envelope.eventType, "NotificationScheduled");
    assert.equal(envelope.schemaVersion, 1);
    assert.equal(envelope.producer, "notification");
    assert.equal(envelope.correlationId, "corr-test-001");
    assert.equal(envelope.causationId, "cmd-test-001");
    assert.equal(envelope.occurredAt, "2026-07-05T10:30:00.000Z");
    assert.deepEqual(envelope.payload, {
      notificationTaskId: "nt-test-001",
      templateCode: "order_confirmed",
      recipientRef: "tvl-test-001",
      channel: "EMAIL",
      intent: "ORDER_CONFIRMED",
      transactionRequired: true,
      scheduledAt: "2026-07-05T10:30:00.000Z",
    });
  });

  it("publishes scheduled notifications through an in-memory publisher without Redis", async () => {
    const publisher = new InMemoryEventPublisher();
    await publisher.publish(toEventEnvelope(domainEvent));

    assert.equal(publisher.envelopes.length, 1);
    assert.equal(publisher.envelopes[0].eventType, "NotificationScheduled");
    assert.equal(publisher.envelopes[0].producer, "notification");
  });

  it("deduplicates subscriber handling by eventId", async () => {
    const seen: string[] = [];
    const dedup = new DeduplicatingEventHandler((envelope) => {
      seen.push(envelope.eventId);
      return successfulHandling();
    });
    const envelope = toEventEnvelope(domainEvent);

    assert.deepEqual(await dedup.handle(envelope), { ok: true });
    assert.deepEqual(await dedup.handle(envelope), { ok: true });

    assert.deepEqual(seen, [envelope.eventId]);
    assert.equal(dedup.hasConsumed(envelope.eventId), true);
  });

  it("turns subscribed upstream events into notification scheduled events", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-upstream-001",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-upstream-001",
      causationId: "cmd-upstream-001",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { recipientRef: "usr-test-001", paymentIntentId: "pi-test-001" },
    };

    await service.handleExternalTrigger(upstream);

    assert.equal(publisher.envelopes.length, 1);
    assert.equal(publisher.envelopes[0].eventType, "NotificationScheduled");
    assert.equal(publisher.envelopes[0].producer, "notification");
    assert.equal(publisher.envelopes[0].correlationId, "corr-upstream-001");
    assert.equal(publisher.envelopes[0].causationId, "evt-upstream-001");
    assert.equal(publisher.envelopes[0].payload.recipientRef, "usr-test-001");
    assert.equal(publisher.envelopes[0].payload.templateCode, "payment_captured");
  });
});
