import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { RedisEventSubscriber } from "./adapters/messaging/subscriber.js";
import { CustomerServiceApplication } from "./application/customer-service.js";
import { EvidenceRef, ManualActionRequest, SupportCase, type CustomerServiceDomainEvent } from "./domain.js";
import { isPrefixedUuidV7 } from "@trainticket/ts-kit";
import { ConsumedEventDeduplicator, InMemoryEventPublisher, InMemoryEventSubscriber, toEventEnvelope, type EventEnvelope } from "./application/messaging.js";

describe("customer-service messaging ports", () => {
  it("wraps domain events in the contract envelope", () => {
    const { event } = SupportCase.open({
      caseId: "sc-test-envelope",
      requesterRef: "tvl-test-envelope",
      channel: "WEB",
      priority: "NORMAL",
      description: "Need help",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
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
    assert.equal(isPrefixedUuidV7(envelope.eventId, ["evt"]), true);
    assert.equal(envelope.eventType, "SupportCaseOpened");
    assert.equal(envelope.occurredAt, "2026-07-05T10:30:00.000Z");
    assert.equal(isPrefixedUuidV7(envelope.correlationId, ["corr"]), true);
    assert.equal(isPrefixedUuidV7(envelope.causationId, ["cmd"]), true);
    assert.equal(envelope.producer, "customer-service");
    assert.equal(envelope.schemaVersion, 1);
    assert.deepEqual(envelope.payload, {
      caseId: "sc-test-envelope",
      requesterRef: "tvl-test-envelope",
      channel: "WEB",
      priority: "NORMAL",
      description: "Need help",
      businessReferences: {},
    });
  });

  it("maps each customer-service domain event payload to the documented contract fields", () => {
    const events: CustomerServiceDomainEvent[] = buildDocumentedPayloadEvents();

    const expectedPayloads: Record<string, Record<string, unknown>> = {
      SupportCaseOpened: {
        caseId: "sc-doc",
        requesterRef: "tvl-doc",
        channel: "APP",
        classification: "PAYMENT_HELP",
        priority: "HIGH",
        description: "Need help",
        businessReferences: { journeyOrderId: "ord-doc" },
      },
      EvidenceAttached: {
        evidenceId: "evid-doc",
        caseId: "sc-doc",
        evidenceType: "SCREENSHOT",
        reference: "s3://evidence/doc",
        summary: "receipt",
        accessLevel: "SENSITIVE",
        attachedBy: "op-doc",
      },
      SupportCaseClassified: { caseId: "sc-doc", classification: "PAYMENT_DISPUTE", priority: "URGENT", classifiedBy: "op-doc" },
      SupportCaseAssigned: { caseId: "sc-doc", ownerQueue: "tier1", assignedTo: "op-owner", assignedBy: "op-doc" },
      SupportCaseEscalated: { caseId: "sc-doc", targetQueue: "tier2", reason: "needs supervisor", escalatedBy: "op-doc" },
      SupportCaseResolved: { caseId: "sc-doc", summary: "fixed", resolutionCode: "FIXED", resolvedBy: "op-doc" },
      ManualActionRequested: {
        manualActionId: "ma-doc",
        caseId: "sc-doc",
        targetDomain: "post-sales",
        commandType: "ManualRefundReviewRequested",
        operatorRef: "op-doc",
        reason: "needs manual review",
        evidenceRefs: ["evid-doc"],
        description: "Review refund",
        requiresApproval: true,
      },
      SupportCaseClosed: { caseId: "sc-doc", reason: "RESOLVED", closedBy: "op-doc" },
      SupportCaseReopened: { caseId: "sc-doc", reason: "still broken", requesterRef: "tvl-doc" },
    };

    for (const event of events) {
      const payload = toEventEnvelope(event).payload;
      assert.deepEqual(payload, expectedPayloads[event.type]);
      assert.equal(Object.hasOwn(payload, "boundaryProof"), false);
    }
  });

  it("deduplicates duplicate eventIds before handling", async () => {
    const deduplicator = new ConsumedEventDeduplicator();
    const envelope: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "JourneyOrderCreated",
      occurredAt: "2026-07-05T10:30:00.000Z",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      producer: "journey-order",
      schemaVersion: 1,
      payload: { journeyOrderId: "ord-1" },
    };
    let handled = 0;

    assert.equal(await deduplicator.handle(envelope, async () => { handled += 1; }), true);
    assert.equal(await deduplicator.handle(envelope, async () => { handled += 1; }), false);
    assert.equal(handled, 1);
  });

  it("attaches journey-order and post-sales facts to matching support case timelines", async () => {
    const publisher = new InMemoryEventPublisher();
    const application = new CustomerServiceApplication(publisher);
    const opened = await application.openSupportCase({
      requesterRef: "tvl-doc",
      channel: "APP",
      priority: "NORMAL",
      description: "Need refund help",
      businessReferences: { journeyOrderId: "ord-doc" },
    }, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");

    await application.handleIntegrationEvent({
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "PostSalesApplied",
      occurredAt: "2026-07-05T10:31:00.000Z",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c221",
      producer: "post-sales",
      schemaVersion: 1,
      payload: { caseId: "psc-doc", orderId: "ord-doc", resultSummary: {} },
    });

    const details = application.getSupportCase(opened.caseId);
    assert.equal(details.timeline.length, 1);
    assert.equal(details.timeline[0].eventType, "PostSalesApplied");
    const timelineEvents = publisher.findByEventType("CaseTimelineEntryAppended");
    assert.deepEqual(timelineEvents.map((e) => e.payload.eventTypeCode), ["PostSalesApplied"]);
    assert.equal(timelineEvents[0].correlationId, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.equal(timelineEvents[0].causationId, "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c221");
  });



  it("records admin-audit manual action outcomes once and preserves event lineage", async () => {
    const publisher = new InMemoryEventPublisher();
    const application = new CustomerServiceApplication(publisher);
    const opened = await application.openSupportCase({
      requesterRef: "tvl-doc",
      channel: "APP",
      priority: "NORMAL",
      description: "Need manual help",
    }, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    const action = await application.requestManualAction(opened.caseId, {
      targetDomain: "post-sales",
      commandType: "ManualRefundReviewRequested",
      operatorRef: "op-doc",
      reason: "needs manual review",
      description: "Review refund",
      requiresApproval: true,
    }, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");

    const upstream: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c333",
      eventType: "ManualActionExecuted",
      occurredAt: "2026-07-05T10:35:00.000Z",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c333",
      producer: "admin-audit",
      schemaVersion: 1,
      payload: { manualActionId: action.manualActionId, targetDomain: "post-sales", targetCommand: "ManualRefundReviewRequested", businessRef: opened.caseId, resultSummary: "Manual action execution recorded" },
    };

    await application.handleIntegrationEvent(upstream);
    await application.handleIntegrationEvent(upstream);

    const resultEvents = publisher.findByEventType("ManualActionResultRecorded");
    assert.equal(resultEvents.length, 1);
    assert.equal(resultEvents[0].correlationId, upstream.correlationId);
    assert.equal(resultEvents[0].causationId, upstream.eventId);
    assert.deepEqual(resultEvents[0].payload, {
      manualActionId: action.manualActionId,
      caseId: opened.caseId,
      outcome: "Succeeded",
      resultSummary: "Manual action execution recorded",
    });
    const details = application.getSupportCase(opened.caseId);
    assert.equal(details.timeline.at(-1)?.eventType, "ManualActionResultRecorded");
    assert.equal(application.consumedIntegrationEventCount(), 1);
  });

  it("Redis subscriber reports background loop failures", async () => {
    class FailingRedis {
      disconnect(): void {}
      async xgroup(): Promise<void> {}
      async xautoclaim(): Promise<unknown[]> { return ["0-0", []]; }
    }
    const observed: unknown[] = [];
    const subscriber = new RedisEventSubscriber(new FailingRedis() as never, (error) => observed.push(error));

    await subscriber.subscribe(["events:journey-order"], "customer-service", "customer-service-test", async () => {});
    await waitFor(() => observed.length > 0, 1200);
    await subscriber.stop();

    assert.equal(observed.length, 1);
    assert.match((observed[0] as Error).message, /background loop failed/);
  });

  it("in-memory subscriber applies consumer-side deduplication", async () => {
    const subscriber = new InMemoryEventSubscriber();
    const envelope: EventEnvelope = {
      eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      eventType: "JourneyOrderConfirmed",
      occurredAt: "2026-07-05T10:31:00.000Z",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      causationId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
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

function buildDocumentedPayloadEvents(): CustomerServiceDomainEvent[] {
  const base = {
    caseId: "sc-doc",
    requesterRef: "tvl-doc",
    channel: "APP" as const,
    classification: "PAYMENT_HELP",
    priority: "HIGH" as const,
    description: "Need help",
    businessReferences: { journeyOrderId: "ord-doc" },
    correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    openedAt: new Date("2026-07-05T10:30:00.000Z"),
  };
  const opened = SupportCase.open(base);
  const attached = EvidenceRef.attach({
    evidenceId: "evid-doc",
    caseId: "sc-doc",
    evidenceType: "SCREENSHOT",
    reference: "s3://evidence/doc",
    summary: "receipt",
    accessLevel: "SENSITIVE",
    attachedBy: "op-doc",
    correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    attachedAt: new Date("2026-07-05T10:31:00.000Z"),
  });
  const classified = opened.case.classify({ caseId: "sc-doc", classification: "PAYMENT_DISPUTE", priority: "URGENT", classifiedBy: "op-doc", classifiedAt: new Date("2026-07-05T10:32:00.000Z") });
  const assigned = classified.case.assign({ caseId: "sc-doc", ownerQueue: "tier1", assignedTo: "op-owner", assignedBy: "op-doc", assignedAt: new Date("2026-07-05T10:33:00.000Z") });
  const escalated = assigned.case.escalate({ caseId: "sc-doc", targetQueue: "tier2", reason: "needs supervisor", escalatedBy: "op-doc", escalatedAt: new Date("2026-07-05T10:34:00.000Z") });
  const manualAction = ManualActionRequest.request({
    manualActionId: "ma-doc",
    caseId: "sc-doc",
    targetDomain: "post-sales",
    commandType: "ManualRefundReviewRequested",
    operatorRef: "op-doc",
    reason: "needs manual review",
    evidenceRefs: ["evid-doc"],
    description: "Review refund",
    requiresApproval: true,
    correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    causationId: "cmd-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
    requestedAt: new Date("2026-07-05T10:34:30.000Z"),
  });
  const resolved = assigned.case.resolve({ caseId: "sc-doc", summary: "fixed", resolutionCode: "FIXED", resolvedBy: "op-doc", resolvedAt: new Date("2026-07-05T10:35:00.000Z") });
  const closed = resolved.case.close({ caseId: "sc-doc", reason: "RESOLVED", closedBy: "op-doc", closedAt: new Date("2026-07-05T10:36:00.000Z") });
  const reopened = closed.case.reopen({ caseId: "sc-doc", reason: "still broken", requesterRef: "tvl-doc", reopenedAt: new Date("2026-07-05T10:37:00.000Z") });
  return [opened.event, attached.event, classified.event, assigned.event, escalated.event, manualAction.event, resolved.event, closed.event, reopened.event];
}

async function waitFor(condition: () => boolean, timeoutMs = 100): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (condition()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  assert.equal(condition(), true);
}
