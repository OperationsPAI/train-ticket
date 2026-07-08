import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp, NotificationApplicationService, type NotificationTaskStore, type EventEnvelope, type NotificationTaskSnapshot } from "./index.js";
import { isDuplicateBusinessNotification } from "./adapters/storage/runtime.js";
import { InMemoryEventPublisher, OptimisticConcurrencyConflict } from "@trainticket/ts-kit";

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

class DuplicateBusinessTaskStore implements NotificationTaskStore {
  public duplicateSkipped = false;
  private seenBusinessKey: string | undefined;

  saveNew(task: { toSnapshot: () => { triggerBusinessRef?: string; recipientRef?: string; templateCode?: string } }): { version: bigint } {
    const snapshot = task.toSnapshot();
    const key = `${snapshot.triggerBusinessRef}:${snapshot.recipientRef}:${snapshot.templateCode}`;
    if (this.seenBusinessKey === key) {
      this.duplicateSkipped = true;
      throw new OptimisticConcurrencyConflict("duplicate business notification");
    }
    this.seenBusinessKey = key;
    return { version: 1n };
  }

  save(_task: unknown, expectedVersion: bigint): { version: bigint } {
    return { version: expectedVersion === 0n ? 0n : expectedVersion + 1n };
  }
}

class FakeDuplicateNotificationClient {
  public snapshot: Pick<NotificationTaskSnapshot, "recipientRef" | "templateCode" | "triggerBusinessRef"> | undefined;

  async query(_sql: string, params: unknown[]): Promise<{ rowCount: number }> {
    const [recipientRef, templateCode, triggerBusinessRef] = params;
    const found = this.snapshot?.recipientRef === recipientRef
      && this.snapshot?.templateCode === templateCode
      && this.snapshot?.triggerBusinessRef === triggerBusinessRef;
    return { rowCount: found ? 1 : 0 };
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
      payload: {
        recipientRef: "usr-test-001",
        paymentIntentId: "pi-test-001",
        businessRef: "ord-test-001",
        capturedAmount: { currency: "CNY", minorUnits: 35000 },
        channel: "wechat_pay",
        channelTransactionId: "wx-test-001",
      },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "delivered");
    assert.deepEqual(store.snapshots.map((snapshot) => (snapshot as { status: string }).status), ["Planned", "Delivering", "Delivered"]);
    assert.deepEqual(publisher.envelopes.map((envelope) => envelope.eventType), ["NotificationScheduled", "NotificationDispatched", "NotificationDelivered"]);
  });

  it("surfaces business duplicate notifications with different event IDs so storage can ack them", async () => {
    const publisher = new InMemoryEventPublisher();
    const store = new DuplicateBusinessTaskStore();
    const service = new NotificationApplicationService(publisher, undefined, undefined, store);
    const first: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
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
    const duplicate = { ...first, eventId: "evt-0194f2e0-7b3f-7c10-8284-5c26e8b0c333" };

    assert.equal(await service.handleExternalTrigger(first), "delivered");
    await assert.rejects(() => service.handleExternalTrigger(duplicate), OptimisticConcurrencyConflict);
    assert.equal(store.duplicateSkipped, true);
  });

  it("detects business duplicate trigger/recipient/template conflicts for storage ack", async () => {
    const client = new FakeDuplicateNotificationClient();
    const first: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PaymentCaptured",
      schemaVersion: 1,
      producer: "payment",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
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
    const duplicate = { ...first, eventId: "evt-0194f2e0-7b3f-7c10-8284-5c26e8b0c333" };
    const publisher = new InMemoryEventPublisher();
    const store = new RecordingTaskStore();
    const service = new NotificationApplicationService(publisher, undefined, undefined, store);

    await service.handleExternalTrigger(first);
    client.snapshot = store.snapshots[0] as NotificationTaskSnapshot;

    assert.equal(await isDuplicateBusinessNotification(client as never, duplicate), true);
  });

  it("marks readiness unavailable while configured storage is not ready", async () => {
    const app = createApp({}, { ready: () => Promise.resolve(false) });

    const response = await app.inject("/readyz");

    assert.equal(response.statusCode, 503);
    assert.deepEqual(response.json(), { status: "not_ready", probe: "ready" });
  });
});
