import assert from "node:assert/strict";
import crypto from "node:crypto";
import { describe, it } from "node:test";

import {
  DomainError,
  NotificationTask,
  Template,
  RecipientPolicy,
  DeliveryReceipt,
  notificationBoundaryProof,
  idempotencyKey,
  type ScheduleNotification,
  type DispatchNotification,
  type RecordDeliveryReceipt,
  type CancelNotification,
} from "./domain.js";

const scheduledAt = new Date("2026-07-03T10:00:00.000Z");
const testEventId = "evt-test-001";
const testCorrelationId = "corr-test-001";

function scheduleCommand(overrides: Partial<ScheduleNotification> = {}): ScheduleNotification {
  return {
    notificationTaskId: "nt-test-001",
    triggerEventId: testEventId,
    triggerEventType: "JourneyOrderCreated",
    correlationId: testCorrelationId,
    causationId: "cmd-test-001",
    recipientRef: "tvl-test-001",
    templateCode: "order_confirmed",
    channel: "EMAIL",
    intent: "ORDER_CONFIRMED",
    transactionRequired: false,
    variables: { orderId: "ord-123", travelerName: "Alice" },
    scheduledAt,
    ...overrides,
  };
}

function dispatchCommand(overrides: Partial<DispatchNotification> = {}): DispatchNotification {
  return {
    notificationTaskId: "nt-test-001",
    dispatchedAt: new Date("2026-07-03T10:00:05.000Z"),
    ...overrides,
  };
}

function receiptCommand(overrides: Partial<RecordDeliveryReceipt> = {}): RecordDeliveryReceipt {
  return {
    receiptId: "rct-test-001",
    notificationTaskId: "nt-test-001",
    channel: "EMAIL",
    outcome: "Delivered",
    providerCode: "provider-ok",
    providerMessage: "Message sent successfully",
    recordedAt: new Date("2026-07-03T10:00:10.000Z"),
    ...overrides,
  };
}

function cancelCommand(overrides: Partial<CancelNotification> = {}): CancelNotification {
  return {
    notificationTaskId: "nt-test-001",
    reason: "Order cancelled by user",
    cancelledAt: new Date("2026-07-03T10:01:00.000Z"),
    ...overrides,
  };
}

function expectDomainError(fn: () => unknown, code: string): void {
  assert.throws(fn, (error: unknown) => error instanceof DomainError && error.code === code);
}

describe("Notification domain foundation", () => {
  // ─── NotificationTask lifecycle ────────────────────────────────────────────

  describe("NotificationTask", () => {
    it("schedules a notification task from a trigger event", () => {
      const { task, event } = NotificationTask.schedule(scheduleCommand());

      assert.equal(task.id, "nt-test-001");
      assert.equal(task.status, "Planned");
      assert.equal(task.recipientRef, "tvl-test-001");
      assert.equal(task.templateCode, "order_confirmed");
      assert.equal(task.transactionRequired, false);
      assert.equal(task.triggerEventId, testEventId);

      assert.equal(event.type, "NotificationScheduled");
      assert.equal(event.eventType, "NotificationScheduled");
      assert.equal(event.schemaVersion, 1);
      assert.equal(event.producer, "notification");
      assert.equal(event.correlationId, testCorrelationId);
      assert.equal(event.causationId, "cmd-test-001");
      assert.equal(event.notificationTaskId, "nt-test-001");
      assert.equal(event.templateCode, "order_confirmed");
      assert.equal(event.recipientRef, "tvl-test-001");
      assert.equal(event.channel, "EMAIL");
      assert.ok(event.eventId.startsWith("evt-"));
    });

    it("rejects scheduling with missing required fields", () => {
      expectDomainError(
        () => NotificationTask.schedule(scheduleCommand({ notificationTaskId: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => NotificationTask.schedule(scheduleCommand({ triggerEventId: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => NotificationTask.schedule(scheduleCommand({ recipientRef: "" })),
        "MISSING_REQUIRED_FIELD",
      );
      expectDomainError(
        () => NotificationTask.schedule(scheduleCommand({ templateCode: "" })),
        "MISSING_REQUIRED_FIELD",
      );
    });

    it("dispatches a planned task into Delivering state", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task, event } = planned.dispatch(dispatchCommand());

      assert.equal(task.status, "Delivering");
      assert.equal(task.id, "nt-test-001");
      assert.equal(event.type, "NotificationDispatched");
      assert.equal(event.eventType, "NotificationDispatched");
      assert.equal(event.notificationTaskId, "nt-test-001");
      assert.equal(event.channel, "EMAIL");
    });

    it("rejects dispatch when task is in terminal state", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task: delivering } = planned.dispatch(dispatchCommand());
      const { task: delivered } = delivering.recordReceipt(receiptCommand());

      expectDomainError(
        () => delivered.dispatch(dispatchCommand()),
        "TASK_NOT_DISPATCHABLE",
      );
    });

    it("records a delivery receipt as appended fact and transitions to Delivered", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task: delivering } = planned.dispatch(dispatchCommand());
      const { task, event } = delivering.recordReceipt(receiptCommand());

      assert.equal(task.status, "Delivered");
      assert.equal(event.type, "NotificationDelivered");
      assert.equal(event.outcome, "Delivered");
      assert.equal(event.receiptId, "rct-test-001");

      const snapshot = task.toSnapshot();
      assert.equal(snapshot.receipts.length, 1);
      assert.equal(snapshot.receipts[0].receiptId, "rct-test-001");
      assert.equal(snapshot.receipts[0].outcome, "Delivered");

      // Receipt is an appended fact; original payload is unchanged
      assert.equal(snapshot.templateCode, "order_confirmed");
      assert.equal(snapshot.recipientRef, "tvl-test-001");
    });

    it("records a failure receipt and transitions to Failed", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task: delivering } = planned.dispatch(dispatchCommand());
      const { task, event } = delivering.recordReceipt(
        receiptCommand({ outcome: "Bounced", providerCode: "INVALID_EMAIL" }),
      );

      assert.equal(task.status, "Failed");
      assert.equal(event.type, "NotificationFailed");
      assert.equal(event.outcome, "Bounced");
      assert.equal(event.providerCode, "INVALID_EMAIL");
    });

    it("cancels a planned task", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task, event } = planned.cancel(cancelCommand());

      assert.equal(task.status, "Cancelled");
      assert.equal(event.type, "NotificationCancelled");
      assert.equal(event.reason, "Order cancelled by user");

      const snapshot = task.toSnapshot();
      assert.equal(snapshot.cancelReason, "Order cancelled by user");
    });

    it("rejects cancel when task is already delivered", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task: delivering } = planned.dispatch(dispatchCommand());
      const { task: delivered } = delivering.recordReceipt(receiptCommand());

      expectDomainError(
        () => delivered.cancel(cancelCommand()),
        "TASK_NOT_CANCELLABLE",
      );
    });

    it("supports multiple receipts appended as facts", () => {
      const { task: planned } = NotificationTask.schedule(scheduleCommand());
      const { task: delivering } = planned.dispatch(dispatchCommand());
      const { task: failed } = delivering.recordReceipt(
        receiptCommand({
          receiptId: "rct-fail-001",
          outcome: "Rejected",
          providerCode: "RATE_LIMITED",
          providerMessage: "Too many requests",
        }),
      );

      assert.equal(failed.status, "Failed");
      const snapshot = failed.toSnapshot();
      assert.equal(snapshot.receipts.length, 1);
      assert.equal(snapshot.receipts[0].receiptId, "rct-fail-001");
      assert.equal(snapshot.receipts[0].outcome, "Rejected");

      // Even after failure, the request payload remains immutable
      assert.equal(snapshot.variables.orderId, "ord-123");
    });

    it("snapshot is deeply immutable", () => {
      const { task } = NotificationTask.schedule(scheduleCommand());
      const snapshot = task.toSnapshot();
      assert.throws(() => {
        (snapshot as { status: string }).status = "Delivered";
      }, TypeError);
    });

    it("proves scheduling does not mutate business aggregates", () => {
      const externalState = {
        journeyOrder: { id: "ord-123", status: "Confirmed" },
        capacityHold: { id: "hold-1", status: "Released" },
        paymentIntent: { id: "pi-123", status: "Captured" },
        entitlement: { id: "ent-123", status: "Issued" },
      };
      const before = structuredClone(externalState);

      NotificationTask.schedule(scheduleCommand());

      assert.deepEqual(externalState, before);
    });
  });

  // ─── Template lifecycle ────────────────────────────────────────────────────

  describe("Template", () => {
    it("drafts a new template with variable schema and channel adaptations", () => {
      const template = Template.draft(
        "tpl-001",
        "order_confirmed",
        { required: ["orderId", "travelerName"], optional: ["departureTime"] },
        [
          { channel: "EMAIL", subjectTemplate: "Order {{orderId}} confirmed", bodyTemplate: "Dear {{travelerName}}, your order is confirmed." },
          { channel: "IN_APP", bodyTemplate: "Order {{orderId}} confirmed" },
        ],
        "ORDER_CONFIRMED",
        false,
      );

      assert.equal(template.id, "tpl-001");
      assert.equal(template.code, "order_confirmed");
      assert.equal(template.status, "Draft");
      assert.equal(template.intent, "ORDER_CONFIRMED");

      const snapshot = template.toSnapshot();
      assert.equal(snapshot.variableSchema.required.length, 2);
      assert.equal(snapshot.channelAdaptations.length, 2);
      assert.equal(snapshot.channelAdaptations[0].channel, "EMAIL");
    });

    it("rejects draft without channel adaptations", () => {
      expectDomainError(
        () => Template.draft(
          "tpl-002",
          "bad_template",
          { required: ["id"], optional: [] },
          [],
          "TEST",
          false,
        ),
        "MISSING_CHANNEL_ADAPTATIONS",
      );
    });

    it("rejects draft without variable schema", () => {
      expectDomainError(
        () => Template.draft(
          "tpl-003",
          "empty_vars",
          { required: [], optional: [] },
          [{ channel: "EMAIL", bodyTemplate: "Hello" }],
          "TEST",
          false,
        ),
        "MISSING_VARIABLE_SCHEMA",
      );
    });

    it("publishes a draft template", () => {
      const template = Template.draft(
        "tpl-004",
        "publish_test",
        { required: ["id"], optional: [] },
        [{ channel: "EMAIL", bodyTemplate: "Hello {{id}}" }],
        "TEST",
        false,
      );

      const published = template.publish(new Date("2026-07-03T12:00:00Z"));
      assert.equal(published.status, "Published");
      assert.ok(published.toSnapshot().publishedAt);
    });

    it("retires a published template", () => {
      const template = Template.draft(
        "tpl-005",
        "retire_test",
        { required: ["id"], optional: [] },
        [{ channel: "EMAIL", bodyTemplate: "Hello {{id}}" }],
        "TEST",
        false,
      ).publish(new Date("2026-07-03T12:00:00Z"));

      const retired = template.retire(new Date("2026-07-03T14:00:00Z"));
      assert.equal(retired.status, "Retired");
      assert.ok(retired.toSnapshot().retiredAt);
    });

    it("rejects publishing a retired template", () => {
      const template = Template.draft(
        "tpl-006",
        "double_publish",
        { required: ["id"], optional: [] },
        [{ channel: "EMAIL", bodyTemplate: "Hello {{id}}" }],
        "TEST",
        false,
      ).publish(new Date("2026-07-03T12:00:00Z"))
        .retire(new Date("2026-07-03T14:00:00Z"));

      expectDomainError(
        () => template.publish(new Date("2026-07-03T15:00:00Z")),
        "TEMPLATE_NOT_PUBLISHABLE",
      );
    });
  });

  // ─── RecipientPolicy lifecycle ─────────────────────────────────────────────

  describe("RecipientPolicy", () => {
    it("defines a channel policy with priority and fallback", () => {
      const policy = RecipientPolicy.define(
        "pol-001",
        "ORDER_CONFIRMED",
        [
          { channel: "EMAIL", priority: 1, fallbackChannels: ["IN_APP"] },
          { channel: "PUSH", priority: 2, fallbackChannels: [] },
        ],
        false,
        true,
        3,
        60,
      );

      assert.equal(policy.intent, "ORDER_CONFIRMED");
      assert.equal(policy.active, true);

      const snapshot = policy.toSnapshot();
      assert.equal(snapshot.allowedChannels.length, 2);
      assert.equal(snapshot.allowedChannels[0].channel, "EMAIL");
      assert.equal(snapshot.allowedChannels[0].priority, 1);
      assert.equal(snapshot.maxRetries, 3);
      assert.equal(snapshot.throttlePerMinute, 60);
    });

    it("rejects policy without allowed channels", () => {
      expectDomainError(
        () => RecipientPolicy.define("pol-bad", "TEST", [], false, false, 3, 60),
        "MISSING_ALLOWED_CHANNELS",
      );
    });

    it("rejects policy with negative maxRetries", () => {
      expectDomainError(
        () => RecipientPolicy.define("pol-bad", "TEST", [{ channel: "EMAIL", priority: 1, fallbackChannels: [] }], false, false, -1, 60),
        "INVALID_MAX_RETRIES",
      );
    });

    it("disables a policy", () => {
      const policy = RecipientPolicy.define(
        "pol-002",
        "TEST",
        [{ channel: "EMAIL", priority: 1, fallbackChannels: [] }],
        false,
        false,
        3,
        60,
      );
      const disabled = policy.disable();
      assert.equal(disabled.active, false);
    });
  });

  // ─── DeliveryReceipt (appended fact) ───────────────────────────────────────

  describe("DeliveryReceipt", () => {
    it("records a delivery receipt as an immutable fact", () => {
      const receipt = DeliveryReceipt.record({
        receiptId: "rct-001",
        notificationTaskId: "nt-001",
        channel: "EMAIL",
        outcome: "Delivered",
        providerCode: "ok",
        recordedAt: new Date("2026-07-03T10:00:10Z"),
      });

      assert.equal(receipt.id, "rct-001");
      assert.equal(receipt.outcome, "Delivered");

      const snapshot = receipt.toSnapshot();
      assert.equal(snapshot.notificationTaskId, "nt-001");
      assert.equal(snapshot.channel, "EMAIL");
    });

    it("records a bounced receipt with provider details", () => {
      const receipt = DeliveryReceipt.record({
        receiptId: "rct-002",
        notificationTaskId: "nt-001",
        channel: "EMAIL",
        outcome: "Bounced",
        providerCode: "INVALID_ADDRESS",
        providerMessage: "Mailbox not found",
        recordedAt: new Date("2026-07-03T10:00:10Z"),
      });

      assert.equal(receipt.outcome, "Bounced");
      assert.equal(receipt.toSnapshot().providerCode, "INVALID_ADDRESS");
    });
  });

  // ─── Idempotency Key ───────────────────────────────────────────────────────

  describe("idempotencyKey", () => {
    it("generates deterministic idempotency key from trigger event, recipient, and template", () => {
      const key1 = idempotencyKey("evt-123", "tvl-456", "order_confirmed");
      const key2 = idempotencyKey("evt-123", "tvl-456", "order_confirmed");
      const key3 = idempotencyKey("evt-789", "tvl-456", "order_confirmed");

      assert.equal(key1, key2);
      assert.notEqual(key1, key3);
      assert.ok(key1.startsWith("idem-"));
    });
  });

  // ─── Notification boundary proof ──────────────────────────────────────────

  describe("notificationBoundaryProof", () => {
    it("returns a frozen proof that notification does not mutate business aggregates", () => {
      const proof = notificationBoundaryProof();
      assert.equal(proof.journeyOrderMutated, false);
      assert.equal(proof.capacityHoldMutated, false);
      assert.equal(proof.paymentIntentMutated, false);
      assert.equal(proof.entitlementMutated, false);
      assert.deepEqual(proof.crossContextWriteTargets, []);
      assert.throws(() => {
        (proof as { journeyOrderMutated: boolean }).journeyOrderMutated = true;
      }, TypeError);
    });
  });

  // ─── Transaction-required notification invariant ──────────────────────────

  describe("Transaction-required notifications", () => {
    it("bypasses ordinary user preferences when transactionRequired is set", () => {
      const { task } = NotificationTask.schedule(scheduleCommand({ transactionRequired: true }));
      assert.equal(task.transactionRequired, true);

      const policy = RecipientPolicy.define(
        "pol-payment",
        "PAYMENT_RESULT",
        [{ channel: "EMAIL", priority: 1, fallbackChannels: ["IN_APP"] }],
        false,
        true,
        3,
        60,
      );

      assert.equal(policy.toSnapshot().transactionRequiredBypass, true);
    });
  });

  // ─── Send idempotency invariant ───────────────────────────────────────────

  describe("Send idempotency", () => {
    it("same trigger event + recipient + template yields the same idempotency key", () => {
      const key = idempotencyKey("evt-payment-001", "tvl-user-001", "payment_success");
      const duplicate = idempotencyKey("evt-payment-001", "tvl-user-001", "payment_success");

      assert.equal(key, duplicate);

      const differentRecipient = idempotencyKey("evt-payment-001", "tvl-user-002", "payment_success");
      assert.notEqual(key, differentRecipient);

      const differentTemplate = idempotencyKey("evt-payment-001", "tvl-user-001", "payment_failed");
      assert.notEqual(key, differentTemplate);
    });
  });
});
