import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  DeduplicatingEventHandler,
  InMemoryEventPublisher,
  NonConformantNotificationTrigger,
  NotificationApplicationService,
  NotificationTask,
  successfulHandling,
  toEventEnvelope,
  type EventEnvelope,
} from "./index.js";
import { isPrefixedUuidV7 } from "@trainticket/ts-kit";

const domainEvent = NotificationTask.schedule({
  notificationTaskId: "nt-test-001",
  triggerEventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
  triggerEventType: "JourneyOrderConfirmed",
  correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
  causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
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
    assert.equal(isPrefixedUuidV7(envelope.eventId, ["evt"]), true);
    assert.equal(isPrefixedUuidV7(envelope.correlationId, ["corr"]), true);
    assert.equal(isPrefixedUuidV7(envelope.causationId ?? "", ["cmd", "evt"]), true);
    assert.equal(envelope.eventType, "NotificationScheduled");
    assert.equal(envelope.schemaVersion, 1);
    assert.equal(envelope.producer, "notification");
    assert.equal(envelope.correlationId, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.equal(envelope.causationId, "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
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
    assert.equal(isPrefixedUuidV7(publisher.envelopes[0].eventId, ["evt"]), true);
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
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { recipientRef: "usr-test-001", paymentIntentId: "pi-test-001" },
    };

    await service.handleExternalTrigger(upstream);

    assert.equal(publisher.envelopes.length, 3);
    assert.equal(publisher.envelopes[0].eventType, "NotificationScheduled");
    assert.equal(publisher.envelopes[1].eventType, "NotificationDispatched");
    assert.equal(publisher.envelopes[2].eventType, "NotificationDelivered");
    assert.equal(isPrefixedUuidV7(publisher.envelopes[0].eventId, ["evt"]), true);
    assert.equal(publisher.envelopes[0].producer, "notification");
    assert.equal(publisher.envelopes[0].correlationId, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.equal(publisher.envelopes[0].causationId, "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.equal(publisher.envelopes[0].payload.recipientRef, "usr-test-001");
    assert.equal(publisher.envelopes[0].payload.templateCode, "payment_captured");
  });

  it("publishes NotificationCancelled when user preferences opt out of non-transactional sends", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(
      publisher,
      { isEnabled: () => false },
    );
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { recipientRef: "usr-test-001", paymentIntentId: "pi-test-001", transactionRequired: false },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "cancelled");
    assert.deepEqual(publisher.envelopes.map((envelope) => envelope.eventType), ["NotificationScheduled", "NotificationCancelled"]);
    assert.equal(publisher.envelopes[1].payload.reason, "SUPPRESSED_BY_PREFERENCES");
  });

  it("bypasses user preferences for transaction-required sends", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(
      publisher,
      { isEnabled: () => false },
    );
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { recipientRef: "usr-test-001", paymentIntentId: "pi-test-001", transactionRequired: true },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "delivered");
    assert.deepEqual(publisher.envelopes.map((envelope) => envelope.eventType), ["NotificationScheduled", "NotificationDispatched", "NotificationDelivered"]);
  });

  it("rejects conformant trigger events that do not identify a recipient", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { paymentIntentId: "pi-test-001" },
    };

    await assert.rejects(
      () => service.handleExternalTrigger(upstream),
      NonConformantNotificationTrigger,
    );
    assert.equal(publisher.envelopes.length, 0);
  });

  it("ignores unsupported upstream event types without scheduling a notification", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "CapacityReleased",
      schemaVersion: 1,
      producer: "capacity-availability",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { recipientRef: "usr-test-001" },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "ignored");
    assert.equal(publisher.envelopes.length, 0);
  });
});
