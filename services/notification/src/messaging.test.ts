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
      ["WAITLIST_PROMOTED", "DISRUPTION_ALERT", "RECOVERY_REACCOMMODATION"],
    );
  });


  it("consumes disruption recovery lifecycle facts as durable in-app traveler notifications", async () => {
    const publisher = new InMemoryEventPublisher();
    const sentChannels: string[] = [];
    const service = new NotificationApplicationService(
      publisher,
      undefined,
      { send: (task) => { sentChannels.push(task.channel); return { ok: true }; } },
    );
    const base = {
      schemaVersion: 1,
      producer: "disruption-recovery",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
      occurredAt: "2026-07-05T10:00:00.000Z",
    } as const;
    const payloadBase = { caseId: "rcv-321", incidentId: "inc-321", journeyOrderId: "ord-321" };

    const events: EventEnvelope[] = [
      {
        ...base,
        eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03210",
        eventType: "RecoveryCaseOpened",
        payload: {
          ...payloadBase,
          disruptionId: "drp-321",
          affectedScope: { journeyOrderId: "ord-321", serviceDate: "2026-07-05", disruptionType: "MISSED_CONNECTION", evidenceRef: "ev-321" },
          openedAt: "2026-07-05T10:00:00.000Z",
          status: "OPENED",
        },
      },
      {
        ...base,
        eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03211",
        eventType: "RecoveryOptionsGenerated",
        payload: {
          ...payloadBase,
          optionSetId: "ros-321",
          options: [
            { optionId: "rop-wait", optionType: "WAIT", title: "等待", description: "等待接续", executionTarget: "NONE" },
            { optionId: "rop-reacc", optionType: "REACCOMMODATION", title: "接续改签", description: "安排替代接续", executionTarget: "TRANSFER_MANAGEMENT", reaccommodation: { connectionId: "conn-321", replacementWindow: { plannedArrivalAt: "2026-07-05T13:00:00.000Z", nextDepartureAt: "2026-07-05T13:30:00.000Z", source: "SYSTEM" } } },
          ],
          requiresUserChoice: true,
          generatedAt: "2026-07-05T10:01:00.000Z",
          status: "AWAITING_USER_CHOICE",
        },
      },
      {
        ...base,
        eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03212",
        eventType: "RecoveryExecutionStarted",
        payload: {
          ...payloadBase,
          optionId: "rop-refund",
          optionType: "REFUND",
          executionId: "rex-321",
          executionTarget: "POST_SALES",
          startedAt: "2026-07-05T10:02:00.000Z",
          status: "EXECUTING_RECOVERY",
        },
      },
      {
        ...base,
        eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03213",
        eventType: "RecoveryCompleted",
        payload: {
          ...payloadBase,
          optionId: "rop-comp",
          optionType: "COMPENSATION",
          executionId: "rex-322",
          externalRef: "ben-321",
          completedAt: "2026-07-05T10:03:00.000Z",
          status: "RECOVERED",
        },
      },
      {
        ...base,
        eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03214",
        eventType: "RecoveryFailed",
        payload: {
          ...payloadBase,
          optionId: "rop-manual",
          executionId: "rex-323",
          failedAt: "2026-07-05T10:04:00.000Z",
          reason: "DOWNSTREAM_UNAVAILABLE",
          nextStatus: "MANUAL_REVIEW",
        },
      },
    ];

    for (const event of events) {
      assert.equal(await service.handleExternalTrigger(event), "delivered");
    }

    assert.deepEqual(sentChannels, ["IN_APP", "IN_APP", "IN_APP", "IN_APP", "IN_APP"]);
    assert.deepEqual(
      publisher.envelopes
        .filter((envelope) => envelope.eventType === "NotificationScheduled")
        .map((envelope) => [envelope.payload.templateType, envelope.payload.intent, envelope.payload.recipientRef, envelope.payload.channel]),
      [
        ["RECOVERY_CASE_OPENED", "RECOVERY_CASE_OPENED", "ord-321", "IN_APP"],
        ["RECOVERY_REACCOMMODATION", "RECOVERY_OPTIONS_AVAILABLE", "ord-321", "IN_APP"],
        ["RECOVERY_EXECUTION_STARTED", "RECOVERY_EXECUTION_STARTED", "ord-321", "IN_APP"],
        ["RECOVERY_COMPENSATION_ISSUED", "RECOVERY_COMPLETED", "ord-321", "IN_APP"],
        ["RECOVERY_FAILED", "RECOVERY_FAILED", "ord-321", "IN_APP"],
      ],
    );
  });

  it("does not present refund or compensation starts as completed recovery outcomes", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);

    for (const [eventId, optionType] of [
      ["evt-0194f2e0-7b3e-7610-8284-5c26e8b03217", "REFUND"],
      ["evt-0194f2e0-7b3e-7610-8284-5c26e8b03218", "COMPENSATION"],
    ] as const) {
      assert.equal(await service.handleExternalTrigger({
        eventId,
        eventType: "RecoveryExecutionStarted",
        schemaVersion: 1,
        producer: "disruption-recovery",
        correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
        causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
        occurredAt: "2026-07-05T10:02:00.000Z",
        payload: {
          caseId: "rcv-321",
          incidentId: "inc-321",
          journeyOrderId: "ord-321",
          optionId: `rop-${optionType.toLowerCase()}`,
          optionType,
          executionId: `rex-${optionType.toLowerCase()}`,
          executionTarget: optionType === "REFUND" ? "POST_SALES" : "WALLET_PROMOTION",
          startedAt: "2026-07-05T10:02:00.000Z",
          status: "EXECUTING_RECOVERY",
        },
      }), "delivered");
    }

    assert.deepEqual(
      publisher.envelopes
        .filter((envelope) => envelope.eventType === "NotificationScheduled")
        .map((envelope) => envelope.payload.templateType),
      ["RECOVERY_EXECUTION_STARTED", "RECOVERY_EXECUTION_STARTED"],
    );
  });

  it("rate-limits disruption recovery in-app notifications", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(
      publisher,
      undefined,
      undefined,
      undefined,
      undefined,
      { checkAndRecord: () => ({ allowed: false, retryAfter: new Date("2026-07-05T11:00:00.000Z") }) },
    );

    await assert.rejects(
      () => service.handleExternalTrigger({
        eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03216",
        eventType: "RecoveryCaseOpened",
        schemaVersion: 1,
        producer: "disruption-recovery",
        correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
        causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
        occurredAt: "2026-07-05T10:00:00.000Z",
        payload: {
          caseId: "rcv-321",
          incidentId: "inc-321",
          disruptionId: "drp-321",
          journeyOrderId: "ord-321",
          affectedScope: { journeyOrderId: "ord-321", serviceDate: "2026-07-05", disruptionType: "DELAY", evidenceRef: "ev-321" },
          openedAt: "2026-07-05T10:00:00.000Z",
          status: "OPENED",
        },
      }),
      (error: Error) => error.name === "RateLimitExceeded",
    );
  });

  it("deduplicates repeated disruption recovery eventIds before scheduling", async () => {
    const publisher = new InMemoryEventPublisher();
    const service = new NotificationApplicationService(publisher);
    const handler = new DeduplicatingEventHandler((envelope) => service.handleExternalTrigger(envelope).then(() => successfulHandling()));
    const event: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b03215",
      eventType: "RecoveryCompleted",
      schemaVersion: 1,
      producer: "disruption-recovery",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c321",
      occurredAt: "2026-07-05T10:05:00.000Z",
      payload: {
        caseId: "rcv-321",
        incidentId: "inc-321",
        journeyOrderId: "ord-321",
        optionId: "rop-reacc",
        optionType: "REACCOMMODATION",
        executionId: "rex-324",
        completedAt: "2026-07-05T10:05:00.000Z",
        status: "RECOVERED",
      },
    };

    assert.deepEqual(await handler.handle(event), { ok: true });
    assert.deepEqual(await handler.handle(event), { ok: true });
    assert.equal(publisher.envelopes.filter((envelope) => envelope.eventType === "NotificationScheduled").length, 1);
  });

});
