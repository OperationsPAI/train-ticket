import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  InMemoryRegistrationAdmission,
  REGISTRATION_ADMISSION_FILE,
  clientKeyFor,
  createApp,
  registrationAdmissionSettings,
  windowStart,
  type RegistrationAdmissionSettings,
} from "./index.js";

const settings: RegistrationAdmissionSettings = {
  windowSeconds: 60,
  maxNewAccountsPerClient: 3,
  clientHeader: "x-forwarded-for",
};

describe("the new-account admission", () => {
  it("states the write path's own ceiling in the settings the image ships", () => {
    const shipped = registrationAdmissionSettings();

    assert.equal(REGISTRATION_ADMISSION_FILE, "registration-admission.json");
    assert.equal(shipped.clientHeader, "x-forwarded-for");
    assert.equal(shipped.windowSeconds, 30);
    // The allowance is the store's measured write path: forty new accounts a
    // second from one client, stated over the window the count is kept for.
    assert.equal(shipped.maxNewAccountsPerClient / shipped.windowSeconds, 40);
    assert.ok(Number.isInteger(shipped.maxNewAccountsPerClient));
  });

  it("counts a window from its own start, not from the first request", () => {
    const windowMs = 30_000;

    assert.equal(windowStart(new Date("2026-09-07T17:03:52.400Z"), 30).getTime(), Date.parse("2026-09-07T17:03:30.000Z"));
    assert.equal(windowStart(new Date("2026-09-07T17:04:00.000Z"), 30).getTime(), Date.parse("2026-09-07T17:04:00.000Z"));
    assert.equal(windowStart(new Date("2026-09-07T17:03:52.400Z"), 30).getTime() % windowMs, 0);
  });

  it("names the client the edge names, and falls back to the address it came from", () => {
    assert.equal(clientKeyFor({ "x-forwarded-for": "203.0.113.7" }, "10.0.0.1", settings), "203.0.113.7");
    // A forwarded chain names the client it saw first.
    assert.equal(clientKeyFor({ "x-forwarded-for": "203.0.113.7, 10.0.0.9" }, undefined, settings), "203.0.113.7");
    assert.equal(clientKeyFor({ "x-forwarded-for": "   " }, "10.0.0.1", settings), "10.0.0.1");
    assert.equal(clientKeyFor({}, "10.0.0.1", settings), "10.0.0.1");
    assert.equal(clientKeyFor({}, undefined, settings), "unknown");
    // A header the deployment did not name is not a client.
    assert.equal(clientKeyFor({ "x-real-ip": "203.0.113.7" }, "10.0.0.1", settings), "10.0.0.1");
  });

  it("admits a client up to its allowance and refuses the request after it", async () => {
    const admission = new InMemoryRegistrationAdmission(settings);
    const at = new Date("2026-09-07T17:03:52.400Z");

    for (let asked = 1; asked <= settings.maxNewAccountsPerClient; asked += 1) {
      assert.equal(await admission.admit("203.0.113.7", at), true, `request ${asked} is inside the allowance`);
    }
    assert.equal(await admission.admit("203.0.113.7", at), false, "the request after it is refused");
    assert.equal(await admission.admit("203.0.113.7", at), false, "and staying refused does not unspend it");
  });

  it("counts each client of a window on its own", async () => {
    const admission = new InMemoryRegistrationAdmission(settings);
    const at = new Date("2026-09-07T17:03:52.400Z");

    for (let asked = 1; asked <= settings.maxNewAccountsPerClient; asked += 1) {
      await admission.admit("203.0.113.7", at);
    }

    assert.equal(await admission.admit("203.0.113.7", at), false);
    assert.equal(await admission.admit("198.51.100.4", at), true, "another client of the same window is untouched");
  });

  it("admits the same client again once its window has turned", async () => {
    const admission = new InMemoryRegistrationAdmission(settings);
    const first = new Date("2026-09-07T17:03:52.400Z");

    for (let asked = 1; asked <= settings.maxNewAccountsPerClient; asked += 1) {
      await admission.admit("203.0.113.7", first);
    }
    assert.equal(await admission.admit("203.0.113.7", first), false);

    assert.equal(await admission.admit("203.0.113.7", new Date("2026-09-07T17:04:00.000Z")), true, "the next window is a fresh allowance");
  });

  it("spends the window once for a registration that is replayed", async () => {
    const app = createApp({
      storage: { ready: () => true, registrationAdmission: new InMemoryRegistrationAdmission(settings) },
    });
    const headers = { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c101", "x-forwarded-for": "203.0.113.7" };
    const create = (key: string) =>
      app.inject({ method: "POST", url: "/api/v1/accounts", headers: { ...headers, "idempotency-key": key }, payload: { accountId: `acct_${key.slice(-4)}` } });

    const first = await create("0194f2e0-7b3e-7610-8284-5c26e8b0c101");
    assert.equal(first.statusCode, 201);

    const replay = await create("0194f2e0-7b3e-7610-8284-5c26e8b0c101");
    assert.equal(replay.statusCode, 201);
    assert.deepEqual(replay.json(), first.json(), "the replay answers what the first request answered");

    // The allowance is three, and the replay must not have spent a second one.
    assert.equal((await create("0194f2e0-7b3e-7610-8284-5c26e8b0c102")).statusCode, 201);
    assert.equal((await create("0194f2e0-7b3e-7610-8284-5c26e8b0c103")).statusCode, 201);
    assert.equal((await create("0194f2e0-7b3e-7610-8284-5c26e8b0c104")).statusCode, 429);
  });

  it("refuses one client without touching the next", async () => {
    const app = createApp({
      storage: {
        ready: () => true,
        registrationAdmission: new InMemoryRegistrationAdmission({ ...settings, maxNewAccountsPerClient: 1 }),
      },
    });
    const create = (key: string, client: string) =>
      app.inject({
        method: "POST",
        url: "/api/v1/accounts",
        headers: { "idempotency-key": key, "x-forwarded-for": client },
        payload: { accountId: `acct_${key.slice(-4)}` },
      });

    assert.equal((await create("0194f2e0-7b3e-7610-8284-5c26e8b0c201", "203.0.113.7")).statusCode, 201);
    assert.equal((await create("0194f2e0-7b3e-7610-8284-5c26e8b0c202", "203.0.113.7")).statusCode, 429);
    assert.equal((await create("0194f2e0-7b3e-7610-8284-5c26e8b0c203", "198.51.100.4")).statusCode, 201);
  });
});
