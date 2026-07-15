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

test("create validates required fields and future deadline", async () => {
  const app = createApp({ now: () => new Date("2026-01-01T00:00:00.000Z") });
  const validPayload = {
    accountId: "acc",
    travelerRef: "tvl-1",
    segmentRef: "seg",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-1",
    itineraryRef: "itin-1",
    intentFingerprint: "intent-valid",
  };

  const requiredFields = ["deadline", "paymentGuaranteeRef", "itineraryRef", "intentFingerprint"] as const;
  for (const [index, field] of requiredFields.entries()) {
    const payload = { ...validPayload };
    delete payload[field];
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/waitlist-requests",
      headers: { "Idempotency-Key": `019f6503-0000-7000-8000-00000000010${index}` },
      payload,
    });
    assert.equal(response.statusCode, 400);
  }

  const pastDeadline = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": "019f6503-0000-7000-8000-000000000104" },
    payload: { ...validPayload, deadline: "2025-01-01T00:00:00.000Z", intentFingerprint: "intent-past" },
  });
  assert.equal(pastDeadline.statusCode, 409);
  await app.close();
});

test("list waitlist requests by traveler returns paginated contract resources", async () => {
  const app = createApp({ now: () => new Date("2026-01-01T00:00:00.000Z") });
  for (const [index, travelerRef] of ["tvl-1", "tvl-1", "tvl-2"].entries()) {
    const create = await app.inject({
      method: "POST",
      url: "/api/v1/waitlist-requests",
      headers: { "Idempotency-Key": `019f6503-0000-7000-8000-00000000020${index}` },
      payload: {
        accountId: "acc",
        travelerRef,
        segmentRef: "seg",
        travelClass: "SECOND",
        deadline: "2026-07-20T00:00:00.000Z",
        paymentGuaranteeRef: `pay-auth-list-${index}`,
        itineraryRef: `itin-${index}`,
        intentFingerprint: `intent-list-${index}`,
      },
    });
    assert.equal(create.statusCode, 201);
  }

  const response = await app.inject({ method: "GET", url: "/api/v1/waitlist-requests?travelerRef=tvl-1&limit=1&offset=0" });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().total, 2);
  assert.equal(response.json().limit, 1);
  assert.equal(response.json().offset, 0);
  assert.equal(response.json().items.length, 1);
  assert.deepEqual(Object.keys(response.json().items[0]).sort(), ["accountId", "deadline", "intentFingerprint", "itineraryRef", "paymentGuaranteeRef", "segmentRef", "status", "travelClass", "travelerRef", "waitlistRequestId"].sort());
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
