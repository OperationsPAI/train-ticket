import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";
import { createApp } from "../src/app.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService } from "../src/application.js";

test("health endpoint returns 200", async () => {
  const app = createApp();
  const response = await app.inject({ method: "GET", url: "/health" });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().status, "ok");
  await app.close();
});

test("contract create/get/cancel endpoints use WaitlistRequest shape and conflict duplicates", async () => {
  const app = createApp();
  const body = { accountId: "acc", travelerRef: "tvl-1", segmentRef: "seg", travelClass: "SECOND", deadline: "2026-07-20T23:59:59.000Z", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itin-1", intentFingerprint: "intent-1" };

  const created = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000001" }, payload: body });
  assert.equal(created.statusCode, 201);
  const resource = created.json();
  assert.deepEqual(Object.keys(resource).sort(), ["accountId", "deadline", "intentFingerprint", "itineraryRef", "paymentGuaranteeRef", "segmentRef", "status", "travelClass", "travelerRef", "waitlistRequestId"].sort());
  assert.equal(resource.status, "QUEUED");
  assert.equal(resource.estimatedWaitMinutes, undefined);

  const missingRequired = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000099" }, payload: { accountId: "acc", travelerRef: "tvl-missing", segmentRef: "seg", itineraryRef: "itin-missing" } });
  assert.equal(missingRequired.statusCode, 400);
  assert.equal(missingRequired.json().code, "VALIDATION_FAILED");

  const duplicate = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000002" }, payload: body });
  assert.equal(duplicate.statusCode, 409);
  assert.equal(duplicate.json().code, "CONFLICT");

  const fetched = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${resource.waitlistRequestId}` });
  assert.equal(fetched.statusCode, 200);
  assert.equal(fetched.json().waitlistRequestId, resource.waitlistRequestId);
  assert.equal(fetched.json().entryId, undefined);

  const cancelled = await app.inject({ method: "POST", url: `/api/v1/waitlist-requests/${resource.waitlistRequestId}/cancel`, headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000003" }, payload: { reason: "USER_REQUESTED" } });
  assert.equal(cancelled.statusCode, 200);
  assert.equal(cancelled.json().status, "CANCELLED");

  const secondBody = { ...body, travelerRef: "tvl-cancel-required", paymentGuaranteeRef: "pay-auth-cancel-required", intentFingerprint: "intent-cancel-required" };
  const second = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000007" }, payload: secondBody });
  const cancelMissingReason = await app.inject({ method: "POST", url: `/api/v1/waitlist-requests/${second.json().waitlistRequestId}/cancel`, headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000008" }, payload: {} });
  assert.equal(cancelMissingReason.statusCode, 400);
  assert.equal(cancelMissingReason.json().code, "VALIDATION_FAILED");
  await app.close();
});

test("archival sweep closes terminal request and contract GET retrieves it", async () => {
  const repository = new InMemoryWaitlistRepository();
  const app = createApp({ repository, publisher: new InMemoryEventPublisher() });
  const body = { accountId: "acc", travelerRef: "tvl-archive", segmentRef: "seg", travelClass: "SECOND", deadline: "2026-07-20T23:59:59.000Z", paymentGuaranteeRef: "pay-auth-archive", itineraryRef: "itin-archive", intentFingerprint: "intent-archive" };
  const created = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000004" }, payload: body });
  const id = created.json().waitlistRequestId;
  await app.inject({ method: "POST", url: `/api/v1/waitlist-requests/${id}/cancel`, headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000005" }, payload: { reason: "USER_REQUESTED" } });

  const swept = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests:archive-sweep", headers: { "idempotency-key": "018f0000-0000-7000-8000-000000000006" }, payload: {} });
  assert.equal(swept.statusCode, 200);
  assert.equal(swept.json().items[0].status, "CLOSED");

  const fetched = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${id}` });
  assert.equal(fetched.statusCode, 200);
  assert.equal(fetched.json().status, "CLOSED");
  assert.equal(fetched.json().waitlistRequestId, id);
  assert.equal(fetched.json().entryId, undefined);
  await app.close();
});

test("cancel publishes deterministic WaitlistCancelled", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, undefined, undefined, undefined, () => new Date("2026-01-01T00:00:00.000Z"));
  const created = await service.join({ accountId: "acc", travelerRef: "tvl-cancel", segmentRef: "seg", travelClass: "SECOND", departureDate: "2026-07-20", deadline: "2026-07-20T23:59:59.000Z", paymentGuaranteeRef: "pay-auth-cancel", itineraryRef: "itin-cancel", intentFingerprint: "intent-cancel" });

  await service.cancel(created.waitlistRequestId, { reason: "USER_REQUESTED" }, "corr-018f0000-0000-7000-8000-000000000001");

  const event = publisher.findByEventType("WaitlistCancelled")[0];
  assert.equal(event.eventId, `waitlist:WaitlistCancelled:${created.waitlistRequestId}:4`);
  assert.equal(event.payload.waitlistRequestId, created.waitlistRequestId);
  assert.equal(event.payload.reason, "USER_REQUESTED");
});
