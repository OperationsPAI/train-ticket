import assert from "node:assert/strict";
import test from "node:test";
import { createApp } from "../src/app.js";

test("health endpoint returns 200", async () => {
  const app = createApp();
  const response = await app.inject({ method: "GET", url: "/health" });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().status, "ok");
  await app.close();
});

test("terminal request is swept closed and remains retrievable as contract resource", async () => {
  const app = createApp({ now: () => new Date("2026-01-01T00:00:00.000Z") });
  const create = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": "019f6503-0000-7000-8000-000000000001" },
    payload: {
      accountId: "acc",
      travelerRef: "tvl-1",
      segmentRef: "seg",
      travelClass: "SECOND",
      deadline: "2026-07-20T00:00:00.000Z",
      paymentGuaranteeRef: "pay-auth-1",
      itineraryRef: "itin-1",
      intentFingerprint: "intent-closed",
    },
  });
  assert.equal(create.statusCode, 201);
  const requestId = create.json().waitlistRequestId;

  const cancel = await app.inject({
    method: "POST",
    url: `/api/v1/waitlist-requests/${requestId}/cancel`,
    headers: { "Idempotency-Key": "019f6503-0000-7000-8000-000000000002" },
    payload: { reason: "user" },
  });
  assert.equal(cancel.statusCode, 200);

  const sweep = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist/archive-sweep",
    headers: { "Idempotency-Key": "019f6503-0000-7000-8000-000000000003" },
  });
  assert.equal(sweep.statusCode, 200);
  assert.equal(sweep.json()[0].status, "CLOSED");

  const get = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${requestId}` });
  assert.equal(get.statusCode, 200);
  assert.deepEqual(Object.keys(get.json()).sort(), ["accountId", "deadline", "intentFingerprint", "itineraryRef", "paymentGuaranteeRef", "segmentRef", "status", "travelClass", "travelerRef", "waitlistRequestId"].sort());
  assert.equal(get.json().status, "CLOSED");
  await app.close();
});
