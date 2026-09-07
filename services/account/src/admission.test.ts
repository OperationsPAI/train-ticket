import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp } from "./app.js";
import {
  InMemoryRegistrationAdmission,
  clientKeyFor,
  registrationAdmissionSettings,
  windowStart,
  type RegistrationAdmissionSettings,
} from "./admission.js";

/**
 * The endpoint that mints an account is the platform's only unauthenticated
 * write and the first call of every journey, so it is the one path where a
 * client's own rate becomes the platform's. These tests cover the budget that
 * bounds it: the settings the service ships, the window arithmetic, the count
 * per client, and the refusal a caller sees when its window is spent.
 */

const tight: RegistrationAdmissionSettings = Object.freeze({
  windowSeconds: 60,
  maxNewAccountsPerClient: 2,
  clientHeader: "x-client-id",
});

function key(sequence: number): string {
  return `0194f2e0-7b3e-7610-8284-5c26e8b0${String(sequence).padStart(4, "0")}`;
}

describe("registration admission settings", () => {
  it("reads the budget the service ships beside its code", () => {
    const settings = registrationAdmissionSettings();

    assert.ok(Number.isInteger(settings.windowSeconds) && settings.windowSeconds > 0);
    assert.ok(Number.isInteger(settings.maxNewAccountsPerClient) && settings.maxNewAccountsPerClient > 0);
    assert.equal(settings.clientHeader, settings.clientHeader.toLowerCase());
    assert.ok(settings.clientHeader.length > 0);
    // Read once: a budget that changed while the process serves would let two
    // replicas of one release enforce two different windows.
    assert.equal(registrationAdmissionSettings(), settings);
  });

  it("starts a window on a boundary, so two calls in one window share a count", () => {
    const at = new Date("2026-09-28T04:50:12.500Z");

    assert.equal(windowStart(at, 60).toISOString(), "2026-09-28T04:50:00.000Z");
    assert.equal(windowStart(at, 60).toISOString(), windowStart(new Date("2026-09-28T04:50:59.999Z"), 60).toISOString());
    assert.equal(windowStart(new Date("2026-09-28T04:51:00.000Z"), 60).toISOString(), "2026-09-28T04:51:00.000Z");
  });

  it("counts the client the edge declares, and the address when it declares none", () => {
    assert.equal(clientKeyFor({ "x-client-id": "edge-7" }, "10.1.2.3", tight), "edge-7");
    assert.equal(clientKeyFor({ "x-client-id": ["edge-8", "edge-9"] }, "10.1.2.3", tight), "edge-8");
    assert.equal(clientKeyFor({ "x-client-id": "203.0.113.9, 70.41.3.18" }, "10.1.2.3", tight), "203.0.113.9");
    assert.equal(clientKeyFor({}, "10.1.2.3", tight), "10.1.2.3");
    assert.equal(clientKeyFor({ "x-client-id": "  " }, undefined, tight), "unknown");
  });
});

describe("registration budget", () => {
describe("the registration endpoint refuses a client whose window is spent", () => {
  function appWithStorage(admission: InMemoryRegistrationAdmission) {
    return createApp({ storage: { ready: async () => true, registrationAdmission: admission } });
  }

  it("answers the third new account of a window with 429 and leaves other routes alone", async () => {
    const app = appWithStorage(new InMemoryRegistrationAdmission(tight));

    for (const sequence of [1, 2]) {
      const created = await app.inject({
        method: "POST",
        url: "/api/v1/accounts",
        headers: { "idempotency-key": key(sequence), "x-client-id": "edge-7" },
        payload: { accountId: `acct_budget_${sequence}` },
      });
      assert.equal(created.statusCode, 201);
    }

    const refused = await app.inject({
      method: "POST",
      url: "/api/v1/accounts",
      headers: { "idempotency-key": key(3), "x-client-id": "edge-7" },
      payload: { accountId: "acct_budget_3" },
    });
    assert.equal(refused.statusCode, 429);
    assert.equal(refused.json().code, "TOO_MANY_REQUESTS");
    assert.match(refused.json().correlationId, /^corr-/);

    // Another client still registers, and the refused client can still read.
    const other = await app.inject({
      method: "POST",
      url: "/api/v1/accounts",
      headers: { "idempotency-key": key(4), "x-client-id": "edge-8" },
      payload: { accountId: "acct_budget_4" },
    });
    assert.equal(other.statusCode, 201);

    const read = await app.inject({ method: "GET", url: "/api/v1/accounts/acct_budget_1" });
    assert.equal(read.statusCode, 200);
  });

  it("does not spend the window twice on a replayed registration", async () => {
    const app = appWithStorage(new InMemoryRegistrationAdmission(tight));
    const registration = {
      method: "POST" as const,
      url: "/api/v1/accounts",
      headers: { "idempotency-key": key(11), "x-client-id": "edge-7" },
      payload: { accountId: "acct_replayed" },
    };

    assert.equal((await app.inject(registration)).statusCode, 201);
    assert.equal((await app.inject(registration)).statusCode, 201);

    const second = await app.inject({
      method: "POST",
      url: "/api/v1/accounts",
      headers: { "idempotency-key": key(12), "x-client-id": "edge-7" },
      payload: { accountId: "acct_replayed_2" },
    });
    assert.equal(second.statusCode, 201);

    const third = await app.inject({
      method: "POST",
      url: "/api/v1/accounts",
      headers: { "idempotency-key": key(13), "x-client-id": "edge-7" },
      payload: { accountId: "acct_replayed_3" },
    });
    assert.equal(third.statusCode, 429);
  });
});
