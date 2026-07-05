import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { RedisEventSubscriber } from "./adapters/messaging/subscriber.js";
import { EvidenceRef, SupportCase, type CustomerServiceDomainEvent } from "./domain.js";
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

  it("Redis subscriber reports background loop failures", async () => {
    class FailingRedis {
      disconnect(): void {}
      async xgroup(): Promise<void> {}
      async xreadgroup(): Promise<unknown> { throw new Error("read failed"); }
      async xautoclaim(): Promise<unknown[]> { return ["0-0", []]; }
    }
    const observed: unknown[] = [];
    const subscriber = new RedisEventSubscriber(new FailingRedis() as never, (error) => observed.push(error));

    await subscriber.subscribe(["events:journey-order"], "customer-service", "customer-service-test", async () => {});
    await waitFor(() => observed.length > 0);
    await subscriber.stop();

    assert.equal(observed.length, 1);
    assert.match((observed[0] as Error).message, /background loop failed/);
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

function buildDocumentedPayloadEvents(): CustomerServiceDomainEvent[] {
  const base = {
    caseId: "sc-doc",
    requesterRef: "tvl-doc",
    channel: "APP" as const,
    classification: "PAYMENT_HELP",
    priority: "HIGH" as const,
    description: "Need help",
    businessReferences: { journeyOrderId: "ord-doc" },
    correlationId: "corr-doc",
    causationId: "cmd-doc",
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
    correlationId: "corr-doc",
    causationId: "cmd-doc",
    attachedAt: new Date("2026-07-05T10:31:00.000Z"),
  });
  const classified = opened.case.classify({ caseId: "sc-doc", classification: "PAYMENT_DISPUTE", priority: "URGENT", classifiedBy: "op-doc", classifiedAt: new Date("2026-07-05T10:32:00.000Z") });
  const assigned = classified.case.assign({ caseId: "sc-doc", ownerQueue: "tier1", assignedTo: "op-owner", assignedBy: "op-doc", assignedAt: new Date("2026-07-05T10:33:00.000Z") });
  const escalated = assigned.case.escalate({ caseId: "sc-doc", targetQueue: "tier2", reason: "needs supervisor", escalatedBy: "op-doc", escalatedAt: new Date("2026-07-05T10:34:00.000Z") });
  const resolved = assigned.case.resolve({ caseId: "sc-doc", summary: "fixed", resolutionCode: "FIXED", resolvedBy: "op-doc", resolvedAt: new Date("2026-07-05T10:35:00.000Z") });
  const closed = resolved.case.close({ caseId: "sc-doc", reason: "RESOLVED", closedBy: "op-doc", closedAt: new Date("2026-07-05T10:36:00.000Z") });
  const reopened = closed.case.reopen({ caseId: "sc-doc", reason: "still broken", requesterRef: "tvl-doc", reopenedAt: new Date("2026-07-05T10:37:00.000Z") });
  return [opened.event, attached.event, classified.event, assigned.event, escalated.event, resolved.event, closed.event, reopened.event];
}

async function waitFor(condition: () => boolean): Promise<void> {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (condition()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  assert.equal(condition(), true);
}
