import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { CustomerServiceApplication } from "./application/customer-service.js";
import { InMemoryEventPublisher, newCorrelationId } from "./application/messaging.js";
import { createApp } from "./index.js";

const openBody = {
  requesterRef: "tvl-0194f2e0-7b3e-7610-8284-5c26e8b001",
  channel: "APP",
  classification: "PAYMENT_HELP",
  priority: "HIGH",
  description: "Payment not reflected after successful charge",
  businessReferences: { journeyOrderId: "ord-0194f2e0-7b3e-7610-8284-5c26e8b002" },
};

let idempotencySequence = 0;

function idempotencyKey(): string {
  idempotencySequence += 1;
  return `018f2e00-7b3e-7610-8284-${idempotencySequence.toString(16).padStart(12, "0")}`;
}

async function openedCase(app = createApp()): Promise<string> {
  const response = await app.inject({ method: "POST", url: "/api/v1/support-cases", headers: { "idempotency-key": idempotencyKey() }, payload: openBody });
  assert.equal(response.statusCode, 201);
  return response.json().caseId as string;
}

describe("customer-service HTTP API", () => {
  it("serves health, liveness, readiness, and metadata with request correlation headers", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/readyz",
      headers: {
        "x-request-id": "req-customer-service-1",
        "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["x-request-id"], "req-customer-service-1");
    assert.equal(response.headers["x-correlation-id"], "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.deepEqual(response.json(), { status: "ok", probe: "ready" });
    assert.equal((await app.inject("/healthz")).statusCode, 200);
    assert.equal((await app.inject("/health")).json().service.serviceId, "customer-service");
    assert.deepEqual((await app.inject("/live")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/livez")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready" });
    assert.equal((await app.inject("/metadata")).json().service.serviceId, "customer-service");
  });

  it("generates request and prefixed correlation ids when headers are absent", async () => {
    const response = await createApp().inject("/livez");
    assert.equal(response.statusCode, 200);
    assert.equal(typeof response.headers["x-request-id"], "string");
    assert.match(String(response.headers["x-correlation-id"]), /^corr-/);
  });

  it("returns the canonical error envelope for missing routes", async () => {
    const response = await createApp().inject({ method: "GET", url: "/missing", headers: { "x-request-id": "req-customer-service-404" } });

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, "NOT_FOUND");
    assert.equal(response.json().message, "Route GET /missing was not found");
    assert.match(response.json().correlationId, /^corr-/);
    assert.deepEqual(response.json().details, {});
  });

  it("opens and gets a support case", async () => {
    const publisher = new InMemoryEventPublisher();
    const app = createApp({ publisher });

    const created = await app.inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c001", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
      payload: openBody,
    });

    assert.equal(created.statusCode, 201);
    assert.match(created.json().caseId, /^sc-/);
    assert.equal(created.json().status, "OPENED");
    assert.equal(created.json().priority, "HIGH");
    assert.equal(publisher.envelopes.length, 1);

    const fetched = await app.inject(`/api/v1/support-cases/${created.json().caseId}`);
    assert.equal(fetched.statusCode, 200);
    assert.equal(fetched.json().caseId, created.json().caseId);
    assert.equal(fetched.json().description, openBody.description);
  });

  it("requires Idempotency-Key and rejects validation failures with canonical body", async () => {
    const missingKey = await createApp().inject({ method: "POST", url: "/api/v1/support-cases", payload: openBody });
    assert.equal(missingKey.statusCode, 400);
    assert.equal(missingKey.json().code, "VALIDATION_FAILED");

    const malformedKey = await createApp().inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": "not-a-uuid-v7" },
      payload: openBody,
    });
    assert.equal(malformedKey.statusCode, 400);
    assert.equal(malformedKey.json().code, "VALIDATION_FAILED");
    assert.equal(malformedKey.json().details.header, "Idempotency-Key");

    const invalid = await createApp().inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c002" },
      payload: { ...openBody, channel: "FAX" },
    });
    assert.equal(invalid.statusCode, 400);
    assert.equal(invalid.json().code, "VALIDATION_FAILED");
    assert.equal(invalid.json().details.field, "channel");
  });

  it("replays idempotent requests and rejects same key with different body", async () => {
    const app = createApp();
    const headers = { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c003" };
    const first = await app.inject({ method: "POST", url: "/api/v1/support-cases", headers, payload: openBody });
    const replay = await app.inject({ method: "POST", url: "/api/v1/support-cases", headers, payload: openBody });
    const reused = await app.inject({ method: "POST", url: "/api/v1/support-cases", headers, payload: { ...openBody, description: "different" } });

    assert.equal(first.statusCode, 201);
    assert.equal(replay.statusCode, 201);
    assert.deepEqual(replay.json(), first.json());
    assert.equal(reused.statusCode, 422);
    assert.equal(reused.json().code, "IDEMPOTENCY_KEY_REUSED");
  });

  it("attaches evidence", async () => {
    const app = createApp();
    const caseId = await openedCase(app);
    const response = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/evidence`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c004" },
      payload: { evidenceType: "SCREENSHOT", reference: "s3://evidence/1", summary: "receipt", accessLevel: "SENSITIVE", attachedBy: "op-1" },
    });
    assert.equal(response.statusCode, 201);
    assert.match(response.json().evidenceId, /^evid-/);
    assert.equal(response.json().evidenceType, "SCREENSHOT");
  });

  it("classifies and assigns a support case", async () => {
    const app = createApp();
    const caseId = await openedCase(app);
    const classified = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/classify`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c005", "x-operator-ref": "op-1" },
      payload: { classification: "PAYMENT_DISPUTE", priority: "URGENT" },
    });
    assert.equal(classified.statusCode, 200);
    assert.equal(classified.json().status, "IN_PROGRESS");

    const assigned = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/assign`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c006", "x-operator-ref": "op-1" },
      payload: { assignedTo: "op-2", ownerQueue: "payment" },
    });
    assert.equal(assigned.statusCode, 200);
    assert.equal(assigned.json().ownerQueue, "payment");
  });

  it("escalates a support case", async () => {
    const app = createApp();
    const caseId = await openedCase(app);
    await app.inject({ method: "POST", url: `/api/v1/support-cases/${caseId}/assign`, headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0d001" }, payload: { ownerQueue: "tier1" } });
    const response = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/escalate`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c007" },
      payload: { targetQueue: "tier2", reason: "needs supervisor" },
    });
    assert.equal(response.statusCode, 200);
    assert.equal(response.json().status, "IN_PROGRESS");
  });

  it("supports customer-requested immediate escalation", async () => {
    const publisher = new InMemoryEventPublisher();
    const app = createApp({ publisher });
    const caseId = await openedCase(app);

    const response = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/escalate`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c018" },
      payload: { targetQueue: "tier2", reason: "customer requested escalation", triggerCondition: "CUSTOMER_REQUEST" },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.json().escalationLevel, "L2_SPECIALIST");
    assert.equal(publisher.findByEventType("TicketEscalated").at(-1)?.payload.triggerCondition, "CUSTOMER_REQUEST");
  });

  it("resolves, closes, and reopens a support case", async () => {
    const app = createApp();
    const caseId = await openedCase(app);
    await app.inject({ method: "POST", url: `/api/v1/support-cases/${caseId}/assign`, headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0d002" }, payload: { ownerQueue: "tier1" } });

    const resolved = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/resolve`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c008" },
      payload: { summary: "fixed", resolutionCode: "FIXED" },
    });
    assert.equal(resolved.statusCode, 200);
    assert.equal(resolved.json().status, "RESOLVED");

    const closed = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/close`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c009" },
      payload: { reason: "RESOLVED" },
    });
    assert.equal(closed.statusCode, 200);
    assert.equal(closed.json().status, "CLOSED");

    const reopened = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/reopen`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c010" },
      payload: { reason: "still broken", requesterRef: openBody.requesterRef },
    });
    assert.equal(reopened.statusCode, 200);
    assert.equal(reopened.json().status, "IN_PROGRESS");
  });

  it("supports customer-request escalation and SLA evaluation endpoints", async () => {
    const publisher = new InMemoryEventPublisher();
    const app = createApp({ publisher });
    const caseId = await openedCase(app);

    const escalated = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/request-escalation`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0d101" },
      payload: { targetQueue: "tier2", reason: "customer asked for escalation" },
    });
    assert.equal(escalated.statusCode, 200);
    assert.equal(escalated.json().escalationHistory.at(-1).triggerCondition, "CUSTOMER_REQUEST");

    const evaluated = await app.inject({
      method: "POST",
      url: "/api/v1/support-cases/evaluate-sla",
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0d102" },
      payload: {},
    });
    assert.equal(evaluated.statusCode, 202);
    assert.equal(evaluated.json().evaluated, 1);
  });

  it("surfaces non-precondition aggregate invariant violations as DOMAIN_RULE_VIOLATION", async () => {
    const app = createApp();
    const caseId = await openedCase(app);
    await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/classify`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c014" },
      payload: { classification: "PAYMENT_DISPUTE", priority: "URGENT" },
    });

    const response = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/classify`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c015" },
      payload: { classification: "PAYMENT_DISPUTE", priority: "URGENT" },
    });

    assert.equal(response.statusCode, 422);
    assert.equal(response.json().code, "DOMAIN_RULE_VIOLATION");
    assert.equal(response.json().details.domainCode, "CASE_NOT_CLASSIFIABLE");
  });

  it("maps resolve and close precondition failures to PRECONDITION_FAILED", async () => {
    const app = createApp();
    const caseId = await openedCase(app);
    const unresolved = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/resolve`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c011" },
      payload: { summary: "fixed", resolutionCode: "FIXED" },
    });
    assert.equal(unresolved.statusCode, 412);
    assert.equal(unresolved.json().code, "PRECONDITION_FAILED");
    assert.equal(unresolved.json().details.domainCode, "CASE_NOT_RESOLVABLE");

    const unclosed = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/close`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c016" },
      payload: { reason: "RESOLVED" },
    });
    assert.equal(unclosed.statusCode, 412);
    assert.equal(unclosed.json().code, "PRECONDITION_FAILED");
    assert.equal(unclosed.json().details.domainCode, "CLOSURE_WITHOUT_RESOLUTION");
  });

  it("uses one generated correlation id for response headers, error bodies, and events", async () => {
    const publisher = new InMemoryEventPublisher();
    const app = createApp({ publisher });

    const created = await app.inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c012" },
      payload: openBody,
    });

    assert.equal(created.statusCode, 201);
    const responseCorrelationId = String(created.headers["x-correlation-id"]);
    assert.match(responseCorrelationId, /^corr-/);
    assert.equal(publisher.envelopes[0].correlationId, responseCorrelationId);

    const invalidOnCreatedCase = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${created.json().caseId}/close`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c013" },
      payload: { reason: "RESOLVED" },
    });

    assert.equal(invalidOnCreatedCase.statusCode, 412);
    assert.equal(invalidOnCreatedCase.json().correlationId, invalidOnCreatedCase.headers["x-correlation-id"]);
  });

  it("requests manual actions idempotently without changing business state", async () => {
    const publisher = new InMemoryEventPublisher();
    const app = createApp({ publisher });
    const caseId = await openedCase(app);
    const payload = {
      targetDomain: "post-sales",
      commandType: "ManualRefundReviewRequested",
      operatorRef: "op-1",
      reason: "needs manual review",
      evidenceRefs: [],
      description: "Review refund evidence",
      requiresApproval: true,
    };

    const first = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/manual-action-requests`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c017" },
      payload,
    });
    const replay = await app.inject({
      method: "POST",
      url: `/api/v1/support-cases/${caseId}/manual-action-requests`,
      headers: { "idempotency-key": "018f2e00-7b3e-7610-8284-5c26e8b0c017" },
      payload,
    });

    assert.equal(first.statusCode, 202);
    assert.match(first.json().manualActionId, /^ma-/);
    assert.equal(first.json().status, "REQUESTED");
    assert.deepEqual(replay.json(), first.json());
    assert.equal(replay.statusCode, 202);
    assert.deepEqual(publisher.findByEventType("ManualActionRequested").map((event) => event.payload.caseId), [caseId]);
    assert.equal(publisher.findByEventType("ManualActionRequested")[0].payload.targetDomain, "post-sales");
  });

  it("offers compensation when resolution SLA breaches", async () => {
    const publisher = new InMemoryEventPublisher();
    const application = new CustomerServiceApplication(publisher);
    const correlationId = newCorrelationId();
    const opened = await application.openSupportCase({
      requesterRef: openBody.requesterRef,
      channel: "APP",
      priority: "URGENT",
      description: openBody.description,
    }, correlationId);

    await application.evaluateEscalationAndSla(opened.caseId, new Date(opened.openedAt.getTime() + 31 * 60_000), correlationId);

    assert.equal(publisher.findByEventType("SlaBreach").some((event) => event.payload.breachType === "RESOLUTION"), true);
    const compensationEvents = publisher.findByEventType("CompensationOffered");
    assert.equal(compensationEvents.length, 1);
    assert.equal(compensationEvents[0].payload.ticketId, opened.caseId);
  });

  it("returns NOT_FOUND for unknown cases", async () => {
    const response = await createApp().inject("/api/v1/support-cases/sc-missing");
    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, "NOT_FOUND");
  });

  it("exposes a no-op-by-default opt-in tracing seam", async () => {
    const seenRequests: Array<{ requestId: string; correlationId: string }> = [];
    const endedSpans: Array<{ method: string; url: string; statusCode: number; requestId: string; correlationId: string }> = [];
    const app = createApp({
      onRequest: (context) => {
        seenRequests.push(context);
      },
      startSpan: (context) => {
        assert.equal(context.method, "GET");
        assert.equal(context.url, "/health");
        return { end: (result) => { endedSpans.push(result); } };
      },
    });

    const response = await app.inject({
      method: "GET",
      url: "/health",
      headers: { "x-request-id": "req-customer-service-trace", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(seenRequests, [{ requestId: "req-customer-service-trace", correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" }]);
    assert.equal(endedSpans[0].statusCode, 200);
  });
});
