import assert from "node:assert/strict";
import test from "node:test";
import { createApp, resetWaitlistStore } from "../src/app.js";

const IDEMPOTENCY_KEY_ONE = "0194f2e0-7b3e-7610-8284-5c26e8b0c123";
const IDEMPOTENCY_KEY_TWO = "0194f2e0-7b3e-7610-8284-5c26e8b0c124";
const IDEMPOTENCY_KEY_THREE = "0194f2e0-7b3e-7610-8284-5c26e8b0c125";
const IDEMPOTENCY_KEY_FOUR = "0194f2e0-7b3e-7610-8284-5c26e8b0c126";

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
    headers: { "Idempotency-Key": IDEMPOTENCY_KEY_ONE },
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

test("create rejects a missing request body", async () => {
  resetWaitlistStore();
  const app = createApp();
  const response = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": IDEMPOTENCY_KEY_ONE },
  });

  assert.equal(response.statusCode, 400);
  assert.equal(response.json().code, "VALIDATION_FAILED");
  assert.equal(response.json().details.domainCode, "VALIDATION_FAILED");
  assert.match(response.json().message, /travelerRef is required/u);

  await app.close();
});

test("create rejects missing singular travelerRef even when legacy travelerRefs is present", async () => {
  resetWaitlistStore();
  const app = createApp();
  const response = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": IDEMPOTENCY_KEY_ONE },
    payload: {
      accountId: "acc-1",
      travelerRefs: ["tvl-legacy"],
      segmentRef: "seg-1",
      deadline: "2026-07-20T00:00:00.000Z",
      paymentGuaranteeRef: "pay-auth-1",
      itineraryRef: "itn-1",
      intentFingerprint: "intent-1",
    },
  });

  assert.equal(response.statusCode, 400);
  assert.equal(response.json().code, "VALIDATION_FAILED");
  assert.equal(response.json().details.domainCode, "VALIDATION_FAILED");
  assert.match(response.json().message, /travelerRef is required/u);

  await app.close();
});

test("create rejects other missing contract-required fields", async () => {
  resetWaitlistStore();
  const app = createApp();
  const requiredFields = ["deadline", "paymentGuaranteeRef", "itineraryRef", "intentFingerprint"] as const;

  for (const [index, field] of requiredFields.entries()) {
    const payload = {
      accountId: "acc-1",
      travelerRef: "tvl-1",
      segmentRef: "seg-1",
      deadline: "2026-07-20T00:00:00.000Z",
      paymentGuaranteeRef: "pay-auth-1",
      itineraryRef: "itn-1",
      intentFingerprint: "intent-1",
    };
    delete payload[field];
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/waitlist-requests",
      headers: { "Idempotency-Key": `0194f2e0-7b3e-7610-8284-5c26e8b0c13${index}` },
      payload,
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.match(response.json().message, new RegExp(`${field} is required`, "u"));
  }

  await app.close();
});

test("duplicate active traveler intent returns conflict", async () => {
  resetWaitlistStore();
  const app = createApp();
  const payload = {
    accountId: "acc-1",
    travelerRef: "tvl-1",
    segmentRef: "seg-1",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-1",
    itineraryRef: "itn-1",
    intentFingerprint: "intent-1",
  };

  const create = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "Idempotency-Key": IDEMPOTENCY_KEY_ONE }, payload });
  assert.equal(create.statusCode, 201);
  const duplicate = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "Idempotency-Key": IDEMPOTENCY_KEY_TWO }, payload: { ...payload, paymentGuaranteeRef: "pay-auth-2" } });

  assert.equal(duplicate.statusCode, 409);
  assert.equal(duplicate.json().code, "CONFLICT");
  assert.equal(duplicate.json().details.domainCode, "CONFLICT");

  await app.close();
});

test("cancel rejects missing or empty reason", async () => {
  resetWaitlistStore();
  const app = createApp();
  const create = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": IDEMPOTENCY_KEY_ONE },
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
  const waitlistRequestId = create.json().waitlistRequestId;

  for (const [index, payload] of [{}, { reason: "   " }].entries()) {
    const response = await app.inject({
      method: "POST",
      url: `/api/v1/waitlist-requests/${waitlistRequestId}/cancel`,
      headers: { "Idempotency-Key": index === 0 ? IDEMPOTENCY_KEY_THREE : IDEMPOTENCY_KEY_FOUR },
      payload,
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.match(response.json().message, /reason is required/u);
  }

  const cancelled = await app.inject({
    method: "POST",
    url: `/api/v1/waitlist-requests/${waitlistRequestId}/cancel`,
    headers: { "Idempotency-Key": IDEMPOTENCY_KEY_TWO },
    payload: { reason: "USER_REQUESTED" },
  });
  assert.equal(cancelled.statusCode, 200);
  assert.equal(cancelled.json().status, "CANCELLED");

  await app.close();
});
