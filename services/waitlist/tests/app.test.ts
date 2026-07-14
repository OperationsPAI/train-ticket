import assert from "node:assert/strict";
import test from "node:test";
import { uuidV7 } from "@trainticket/ts-kit";
import { createApp } from "../src/app.js";

test("health endpoint returns 200", async () => {
  const app = createApp();
  const response = await app.inject({ method: "GET", url: "/health" });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().status, "ok");
  await app.close();
});

test("contract create/get/cancel paths use WaitlistRequest resource shape", async () => {
  const app = createApp({ now: () => new Date("2026-01-01T00:00:00.000Z") });
  const body = {
    accountId: "acc-contract",
    travelerRef: "tvl-contract",
    segmentRef: "seg-contract",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-contract",
    itineraryRef: "itin-contract",
    intentFingerprint: "tvl-contract:seg-contract",
  };

  const created = await app.inject({ method: "POST", url: "/api/v1/waitlist-requests", headers: { "idempotency-key": uuidV7() }, payload: body });
  assert.equal(created.statusCode, 201);
  const resource = created.json();
  assert.equal(resource.status, "QUEUED");
  assert.equal(resource.waitlistRequestId.startsWith("wlr-"), true);
  assert.equal(resource.entryId, undefined);

  const fetched = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${resource.waitlistRequestId}` });
  assert.equal(fetched.statusCode, 200);
  assert.deepEqual(fetched.json(), resource);

  const cancelled = await app.inject({ method: "POST", url: `/api/v1/waitlist-requests/${resource.waitlistRequestId}/cancel`, headers: { "idempotency-key": uuidV7() }, payload: { reason: "CUSTOMER" } });
  assert.equal(cancelled.statusCode, 200);
  assert.equal(cancelled.json().status, "CANCELLED");
  await app.close();
});
