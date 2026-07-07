import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp, NotificationApplicationService, type NotificationTaskStore, type EventEnvelope } from "./index.js";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";

class RecordingTaskStore implements NotificationTaskStore {
  public readonly snapshots: unknown[] = [];
  private nextVersion = 1n;

  saveNew(task: { toSnapshot: () => unknown }): { version: bigint } {
    this.snapshots.push(task.toSnapshot());
    return { version: this.nextVersion++ };
  }

  save(task: { toSnapshot: () => unknown }, expectedVersion: bigint): { version: bigint } {
    assert.equal(expectedVersion, this.nextVersion - 1n);
    this.snapshots.push(task.toSnapshot());
    return { version: this.nextVersion++ };
  }
}

describe("notification PostgreSQL pilot seams", () => {
  it("persists notification task snapshots through the application store port", async () => {
    const publisher = new InMemoryEventPublisher();
    const store = new RecordingTaskStore();
    const service = new NotificationApplicationService(publisher, undefined, undefined, store);
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

    assert.equal(await service.handleExternalTrigger(upstream), "delivered");
    assert.deepEqual(store.snapshots.map((snapshot) => (snapshot as { status: string }).status), ["Planned", "Delivering", "Delivered"]);
    assert.deepEqual(publisher.envelopes.map((envelope) => envelope.eventType), ["NotificationScheduled", "NotificationDispatched", "NotificationDelivered"]);
  });

  it("reads IN_APP notification query data through the storage adapter when configured", async () => {
    const app = createApp({}, {
      ready: () => true,
      listInAppNotifications: async (recipientRef) => [{ notificationTaskId: "nt-1", recipientRef, channel: "IN_APP" }],
    });

    const response = await app.inject({ method: "GET", url: "/api/v1/in-app-notifications?recipientRef=usr-test-001" });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { notifications: [{ notificationTaskId: "nt-1", recipientRef: "usr-test-001", channel: "IN_APP" }] });
  });

  it("marks readiness unavailable while configured storage is not ready", async () => {
    const app = createApp({}, { ready: () => false, listInAppNotifications: async () => [] });

    const response = await app.inject("/readyz");

    assert.equal(response.statusCode, 503);
    assert.deepEqual(response.json(), { status: "not_ready", probe: "ready" });
  });
});
