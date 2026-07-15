import assert from "node:assert/strict";
import test from "node:test";
import { createApp, resetWaitlistStore } from "../src/app.js";

test("health endpoint returns 200", async () => {
  const app = createApp();
  const response = await app.inject({ method: "GET", url: "/health" });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().status, "ok");
  await app.close();
});

test("waitlist request endpoints return contract resource shape", async () => {
  resetWaitlistStore();
  const app = createApp();
  const create = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c123" },
    payload: {
      accountId: "acc-1",
      travelerRef: "tvl-1",
      segmentRef: "seg-1",
      travelClass: "SECOND",
      deadline: "2026-07-20T00:00:00.000Z",
      paymentGuaranteeRef: "pay-auth-1",
      itineraryRef: "itn-1",
      intentFingerprint: "intent-1",
    },
  });
  const resource = create.json();
  assert.equal(create.statusCode, 201);
  assert.deepEqual(Object.keys(resource).sort(), [
    "accountId",
    "deadline",
    "intentFingerprint",
    "itineraryRef",
    "paymentGuaranteeRef",
    "segmentRef",
    "status",
    "travelClass",
    "travelerRef",
    "waitlistRequestId",
  ]);
  assert.equal(resource.status, "QUEUED");
  assert.equal(resource.waitlistRequestId.startsWith("wlr-"), true);

  const get = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${resource.waitlistRequestId}` });
  assert.equal(get.statusCode, 200);
  assert.deepEqual(get.json(), resource);

  const list = await app.inject({ method: "GET", url: "/api/v1/waitlist-requests?travelerRef=tvl-1" });
  assert.equal(list.statusCode, 200);
  assert.equal(list.json().items[0].waitlistRequestId, resource.waitlistRequestId);

  await app.close();
});
