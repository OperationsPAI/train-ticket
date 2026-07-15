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

test("create rejects missing travelerRef even when legacy travelerRefs is supplied", async () => {
  resetWaitlistStore();
  const app = createApp();
  const response = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c124" },
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

test("create rejects all contract-required fields that were previously defaulted", async () => {
  const fields = ["accountId", "segmentRef", "deadline", "paymentGuaranteeRef", "itineraryRef", "intentFingerprint"];
  for (const [index, field] of fields.entries()) {
    resetWaitlistStore();
    const app = createApp();
    const payload: Record<string, unknown> = {
      accountId: "acc-1",
      travelerRef: "tvl-1",
      segmentRef: "seg-1",
      deadline: "2026-07-20T00:00:00.000Z",
      paymentGuaranteeRef: "pay-auth-1",
      itineraryRef: "itn-1",
      intentFingerprint: "intent-1",
    };
    delete payload[field];

    const idempotencyKey = [
      "0194f2e0-7b3e-7610-8284-5c26e8b0c125",
      "0194f2e0-7b3e-7610-8284-5c26e8b0c126",
      "0194f2e0-7b3e-7610-8284-5c26e8b0c127",
      "0194f2e0-7b3e-7610-8284-5c26e8b0c128",
      "0194f2e0-7b3e-7610-8284-5c26e8b0c129",
      "0194f2e0-7b3e-7610-8284-5c26e8b0c130",
    ][index] ?? "0194f2e0-7b3e-7610-8284-5c26e8b0c125";
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/waitlist-requests",
      headers: { "Idempotency-Key": idempotencyKey },
      payload,
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.match(response.json().message, new RegExp(`${field} is required`, "u"));
    await app.close();
  }
});

test("create rejects malformed bodies with validation error", async () => {
  const cases = [
    { payload: "null", message: /Request body must be a JSON object/u },
    { payload: "", message: /Body cannot be empty|Request body must be a JSON object/u },
    { payload: "{", message: /Body is not valid JSON|Unexpected end/u },
  ];

  for (const [index, testCase] of cases.entries()) {
    resetWaitlistStore();
    const app = createApp();
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/waitlist-requests",
      headers: { "Idempotency-Key": [
        "0194f2e0-7b3e-7610-8284-5c26e8b0c129",
        "0194f2e0-7b3e-7610-8284-5c26e8b0c136",
        "0194f2e0-7b3e-7610-8284-5c26e8b0c137",
      ][index] ?? "0194f2e0-7b3e-7610-8284-5c26e8b0c129", "content-type": "application/json" },
      payload: testCase.payload,
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.match(response.json().message, testCase.message);
    await app.close();
  }
});

test("create uses documented travelerRef when legacy travelerRefs is also present", async () => {
  resetWaitlistStore();
  const app = createApp();
  const payload = {
    accountId: "acc-1",
    travelerRef: "tvl-contract",
    travelerRefs: ["tvl-legacy"],
    segmentRef: "seg-1",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-1",
    itineraryRef: "itn-1",
    intentFingerprint: "intent-contract-canonical",
  };

  const first = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c138" }, payload });
  const duplicate = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c139" }, payload: { ...payload, travelerRefs: ["tvl-other-legacy"] } });

  assert.equal(first.statusCode, 201);
  assert.equal(first.json().travelerRef, "tvl-contract");
  assert.equal(duplicate.statusCode, 409);
  assert.equal(duplicate.json().code, "CONFLICT");
  await app.close();
});

test("list requires travelerRef query parameter", async () => {
  resetWaitlistStore();
  const app = createApp();

  for (const url of ["/api/v1/waitlist-requests", "/api/v1/waitlist-requests?travelerRef="]) {
    const response = await app.inject({ method: "GET", url });
    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.match(response.json().message, /travelerRef is required/u);
  }

  await app.close();
});

test("duplicate active traveler and intent returns conflict", async () => {
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
    intentFingerprint: "intent-duplicate",
  };

  const first = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c130" }, payload });
  const duplicate = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c131" }, payload: { ...payload, segmentRef: "seg-2" } });

  assert.equal(first.statusCode, 201);
  assert.equal(duplicate.statusCode, 409);
  assert.equal(duplicate.json().code, "CONFLICT");
  assert.equal(duplicate.json().details.domainCode, "CONFLICT");
  await app.close();
});

test("on-demand archival sweep closes cancelled request and contract GET still retrieves it", async () => {
  resetWaitlistStore();
  const app = createApp();
  const create = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c140" },
    payload: {
      accountId: "acc-1",
      travelerRef: "tvl-archive",
      segmentRef: "seg-archive",
      travelClass: "SECOND",
      deadline: "2026-07-20T00:00:00.000Z",
      paymentGuaranteeRef: "pay-auth-archive",
      itineraryRef: "itn-archive",
      intentFingerprint: "intent-archive",
    },
  });
  assert.equal(create.statusCode, 201);
  const waitlistRequestId = create.json().waitlistRequestId;

  const cancel = await app.inject({ method: "POST", url: `/api/v1/waitlist-requests/${waitlistRequestId}/cancel`, headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c141" }, payload: { reason: "traveler requested cancellation" } });
  assert.equal(cancel.statusCode, 200);

  const queueBefore = await app.inject({ method: "GET", url: "/api/v1/waitlist/segments/seg-archive/2026-07-20/queue" });
  assert.equal(queueBefore.json().totalQueued, 0);

  const sweep = await app.inject({ method: "POST", url: "/api/v1/waitlist/archive-sweep", headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c142" }, payload: {} });
  assert.equal(sweep.statusCode, 200);
  assert.equal(sweep.json().archived[0].waitlistRequestId, waitlistRequestId);
  assert.equal(sweep.json().archived[0].status, "CLOSED");

  const get = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${waitlistRequestId}` });
  assert.equal(get.statusCode, 200);
  assert.deepEqual(Object.keys(get.json()).sort(), [
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
  assert.equal(get.json().status, "CLOSED");

  const queueAfter = await app.inject({ method: "GET", url: "/api/v1/waitlist/segments/seg-archive/2026-07-20/queue" });
  assert.equal(queueAfter.json().totalQueued, 0);
  await app.close();
});

test("cancel requires reason and handles null body defensively", async () => {
  const invalidBodies = [{}, { reason: "" }, "null"];
  for (const [index, payload] of invalidBodies.entries()) {
    resetWaitlistStore();
    const app = createApp();
    const create = await app.inject({
      method: "POST",
      url: "/api/v1/waitlist-requests",
      headers: { "Idempotency-Key": "0194f2e0-7b3e-7610-8284-5c26e8b0c132" },
      payload: {
        accountId: "acc-1",
        travelerRef: "tvl-1",
        segmentRef: "seg-1",
        travelClass: "SECOND",
        deadline: "2026-07-20T00:00:00.000Z",
        paymentGuaranteeRef: "pay-auth-1",
        itineraryRef: "itn-1",
        intentFingerprint: "intent-cancel",
      },
    });

    const response = await app.inject({
      method: "POST",
      url: `/api/v1/waitlist-requests/${create.json().waitlistRequestId}/cancel`,
      headers: { "Idempotency-Key": [
        "0194f2e0-7b3e-7610-8284-5c26e8b0c133",
        "0194f2e0-7b3e-7610-8284-5c26e8b0c134",
        "0194f2e0-7b3e-7610-8284-5c26e8b0c135",
      ][index] ?? "0194f2e0-7b3e-7610-8284-5c26e8b0c133", "content-type": "application/json" },
      payload,
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    await app.close();
  }
});
