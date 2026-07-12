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
      payload: {
        recipientRef: "usr-test-001",
        paymentIntentId: "pi-test-001",
        businessRef: "ord-test-001",
        capturedAmount: { currency: "CNY", minorUnits: 35000 },
        channel: "wechat_pay",
        channelTransactionId: "wx-test-001",
      },
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
      payload: {
        recipientRef: "usr-test-001",
        paymentIntentId: "pi-test-001",
        businessRef: "ord-test-001",
        capturedAmount: { currency: "CNY", minorUnits: 35000 },
        channel: "wechat_pay",
        channelTransactionId: "wx-test-001",
        transactionRequired: false,
      },
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
      payload: {
        recipientRef: "usr-test-001",
        paymentIntentId: "pi-test-001",
        businessRef: "ord-test-001",
        capturedAmount: { currency: "CNY", minorUnits: 35000 },
        channel: "wechat_pay",
        channelTransactionId: "wx-test-001",
        transactionRequired: true,
      },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "delivered");
    assert.deepEqual(publisher.envelopes.map((envelope) => envelope.eventType), ["NotificationScheduled", "NotificationDispatched", "NotificationDelivered"]);
  });

  it("ack-skips conformant trigger events that do not identify a recipient", async () => {
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
      payload: {
        paymentIntentId: "pi-test-001",
        businessRef: "ord-test-001",
        capturedAmount: { currency: "CNY", minorUnits: 35000 },
        channel: "wechat_pay",
        channelTransactionId: "wx-test-001",
      },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "ignored");
    assert.equal(publisher.envelopes.length, 0);
  });

  it("ack-skips mapped post-sales triggers when no recipient can be derived", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c224",
      eventType: "PostSalesExecutionStarted",
      schemaVersion: 1,
      producer: "post-sales",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { caseId: "psc-test-001", orderedSteps: ["EXECUTE_REFUND"], approvalRef: "apr-test-001" },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "ignored");
    assert.equal(publisher.envelopes.length, 0);
  });

  it("rejects post-sales triggers that violate their own event contract", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c225",
      eventType: "PostSalesExecutionStarted",
      schemaVersion: 1,
      producer: "post-sales",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      // Missing contract-required orderedSteps + approvalRef.
      payload: { caseId: "psc-test-001" },
    };

    await assert.rejects(
      async () => service.handleExternalTrigger(upstream),
      (error: Error) => error.name === "NonConformantNotificationTrigger",
    );
  });

  it("rejects trigger events that violate their own event contract", async () => {
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
      payload: {
        businessRef: "ord-test-001",
        capturedAmount: { currency: "CNY", minorUnits: 35000 },
        channel: "wechat_pay",
        channelTransactionId: "wx-test-001",
      },
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

  it("notifies account recipients when wallet benefits are issued", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c226",
      eventType: "BenefitIssued",
      schemaVersion: 1,
      producer: "wallet-promotion",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: {
        benefitId: "ben-test-001",
        accountId: "acc-test-001",
        issuedAmount: { currency: "CNY", minorUnits: 1000 },
        issuanceSource: "MANUAL_OPS",
        issuedAt: "2026-07-05T10:00:00.000Z",
      },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "delivered");
    assert.equal(publisher.envelopes[0].eventType, "NotificationScheduled");
    assert.equal(publisher.envelopes[0].payload.recipientRef, "acc-test-001");
    assert.equal(publisher.envelopes[0].payload.templateCode, "wallet_benefit_issued");
  });

  it("does not notify noisy wallet benefit redemptions", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c227",
      eventType: "BenefitRedeemed",
      schemaVersion: 1,
      producer: "wallet-promotion",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { benefitId: "ben-test-001", accountId: "acc-test-001" },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "ignored");
    assert.equal(publisher.envelopes.length, 0);
  });

  it("ack-skips unknown wallet event types", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c228",
      eventType: "BenefitReserved",
      schemaVersion: 1,
      producer: "wallet-promotion",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { benefitId: "ben-test-001", accountId: "acc-test-001" },
    };

    assert.equal(await service.handleExternalTrigger(upstream), "ignored");
    assert.equal(publisher.envelopes.length, 0);
  });


  it("falls back from unavailable PUSH to SMS", async () => {
    const publisher = new InMemoryEventPublisher();
    const sentChannels: string[] = [];
    const service = new NotificationApplicationService(
      publisher,
      undefined,
      { send: (task) => { sentChannels.push(task.channel); return { ok: true }; } },
      undefined,
      { getContactProfile: () => ({ phoneNumber: "+8613800000000", emailAddress: "u@example.test" }) },
    );

    assert.equal(await service.handleExternalTrigger({
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c229",
      eventType: "JourneyOrderConfirmed",
      schemaVersion: 1,
      producer: "journey-order",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: {
        orderId: "ord-test-001",
        accountId: "acc-test-001",
        monetarySummary: {},
        confirmedAt: "2026-07-05T10:00:00.000Z",
        origin: "北京",
        destination: "上海",
        departureTime: "12:00",
      },
    }), "delivered");
    assert.deepEqual(sentChannels, ["SMS"]);
  });

  it("falls back from failed SMS to EMAIL", async () => {
    const publisher = new InMemoryEventPublisher();
    const sentChannels: string[] = [];
    const service = new NotificationApplicationService(
      publisher,
      undefined,
      { send: (task) => {
        sentChannels.push(task.channel);
        return task.channel === "SMS" ? { ok: false, outcome: "Rejected" } : { ok: true };
      } },
      undefined,
      { getContactProfile: () => ({ phoneNumber: "+8613800000000", emailAddress: "u@example.test", preferredChannel: "SMS" }) },
    );

    assert.equal(await service.handleExternalTrigger({
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c230",
      eventType: "RefundSettled",
      schemaVersion: 1,
      producer: "post-sales",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: { recipientRef: "usr-test-001", refundId: "ref-1", paymentIntentId: "pi-1", amount: { currency: "CNY", minorUnits: 1200 } },
    }), "delivered");
    assert.deepEqual(sentChannels, ["SMS", "EMAIL"]);
    assert.deepEqual(publisher.envelopes.map((envelope) => envelope.eventType), ["NotificationScheduled", "NotificationDispatched", "NotificationFailed", "NotificationDelivered"]);
  });

  it("maps documented waitlist and disruption recovery events", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);

    await service.handleExternalTrigger({
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c231",
      eventType: "WaitlistFulfilled",
      schemaVersion: 1,
      producer: "waitlist",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:00:00.000Z",
      payload: {
        waitlistRequestId: "wlr-1",
        accountId: "acc-1",
        travelerRef: "tvl-1",
        segmentRef: "seg-1",
        journeyOrderRef: "ord-1",
        fulfilledAt: "2026-07-05T10:00:00.000Z",
        status: "FULFILLED",
      },
    });
    await service.handleExternalTrigger({
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c232",
      eventType: "ServiceAlertPublished",
      schemaVersion: 1,
      producer: "disruption-recovery",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:01:00.000Z",
      payload: {
        serviceAlertId: "sal-1",
        incidentId: "inc-1",
        disruptionType: "DELAY",
        scheduledServiceRef: "G123",
        serviceDate: "2026-07-05",
        audience: "AFFECTED_ORDERS",
        affectedOrderIds: ["ord-2"],
        messageSummary: "列车晚点",
        publishedAt: "2026-07-05T10:01:00.000Z",
      },
    });
    await service.handleExternalTrigger({
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c233",
      eventType: "RecoveryCompleted",
      schemaVersion: 1,
      producer: "disruption-recovery",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      occurredAt: "2026-07-05T10:02:00.000Z",
      payload: {
        caseId: "rcv-1",
        incidentId: "inc-1",
        journeyOrderId: "ord-3",
        optionId: "rop-1",
        optionType: "REACCOMMODATION",
        executionId: "rex-1",
        externalRef: "conn-2",
        completedAt: "2026-07-05T10:02:00.000Z",
        status: "RECOVERED",
      },
    });

    assert.deepEqual(
      publisher.envelopes
        .filter((envelope) => envelope.eventType === "NotificationScheduled")
        .map((envelope) => envelope.payload.templateType),
      ["WAITLIST_PROMOTED", "DELAY_ALERT", "DISRUPTION_REBOOK"],
    );
  });

});
