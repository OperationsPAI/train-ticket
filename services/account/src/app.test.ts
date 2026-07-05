import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp, InMemoryEventPublisher } from "./index.js";

describe("account service operational foundation", () => {
  it("serves health, liveness, readiness, and metadata with request correlation headers", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/readyz",
      headers: {
        "x-request-id": "req-account-1",
        "x-correlation-id": "corr-account-1",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["x-request-id"], "req-account-1");
    assert.equal(response.headers["x-correlation-id"], "corr-account-1");
    assert.deepEqual(response.json(), { status: "ok", probe: "ready" });

    const health = await app.inject("/health");
    assert.equal(health.statusCode, 200);
    assert.equal(health.json().status, "ok");
    assert.equal(health.json().service.serviceId, "account");

    assert.deepEqual((await app.inject("/live")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/livez")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready" });

    const metadata = await app.inject("/metadata");
    assert.equal(metadata.statusCode, 200);
    assert.equal(metadata.json().service.serviceId, "account");
    assert.deepEqual(metadata.json().observability, { tracing: "opt-in", default: "noop" });
  });

  it("generates request and correlation ids when headers are absent", async () => {
    const app = createApp();

    const response = await app.inject("/livez");

    assert.equal(response.statusCode, 200);
    assert.equal(typeof response.headers["x-request-id"], "string");
    assert.notEqual(response.headers["x-request-id"], "");
    assert.equal(typeof response.headers["x-correlation-id"], "string");
    assert.match(String(response.headers["x-correlation-id"]), /^corr-/);
  });

  it("returns the standard error envelope for missing routes", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/missing",
      headers: { "x-request-id": "req-account-404" },
    });

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, "NOT_FOUND");
    assert.equal(response.json().message, "Route GET /missing was not found");
    assert.match(response.json().correlationId, /^corr-/);
    assert.deepEqual(response.json().details, {});
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
        return {
          end: (result) => {
            endedSpans.push(result);
          },
        };
      },
    });

    const response = await app.inject({
      method: "GET",
      url: "/health",
      headers: {
        "x-request-id": "req-account-trace",
        "x-correlation-id": "corr-account-trace",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(seenRequests, [{ requestId: "req-account-trace", correlationId: "corr-account-trace" }]);
    assert.deepEqual(endedSpans, [
      {
        method: "GET",
        url: "/health",
        statusCode: 200,
        requestId: "req-account-trace",
        correlationId: "corr-account-trace",
      },
    ]);

    assert.equal((await createApp().inject("/health")).statusCode, 200);
  });
});


describe("account HTTP API", () => {
  it("creates and fetches an account", async () => {
    const app = createApp();

    const create = await app.inject({
      method: "POST",
      url: "/api/v1/accounts",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c001", "x-correlation-id": "corr-http-create" },
      payload: { accountId: "acct_http_001" },
    });

    assert.equal(create.statusCode, 201);
    assert.equal(create.json().accountId, "acct_http_001");
    assert.equal(create.json().status, "ACTIVE");
    assert.equal(typeof create.json().createdAt, "string");

    const get = await app.inject("/api/v1/accounts/acct_http_001");
    assert.equal(get.statusCode, 200);
    assert.equal(get.json().accountId, "acct_http_001");
  });

  it("freezes and unfreezes an account through the domain aggregate", async () => {
    const app = createApp();
    await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c002" }, payload: { accountId: "acct_freeze" } });

    const freeze = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_freeze/freeze",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c003" },
      payload: { reason: "risk", operator: "ops", caseRef: "case-1" },
    });
    assert.equal(freeze.statusCode, 200);
    assert.equal(freeze.json().status, "FROZEN");

    const unfreeze = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_freeze/unfreeze",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c004" },
      payload: { reason: "resolved" },
    });
    assert.equal(unfreeze.statusCode, 200);
    assert.equal(unfreeze.json().status, "ACTIVE");
  });

  it("updates preferences and starts account closure", async () => {
    const app = createApp();
    await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c005" }, payload: { accountId: "acct_pref" } });

    const pref = await app.inject({
      method: "PATCH",
      url: "/api/v1/accounts/acct_pref/preferences",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c006" },
      payload: { preferenceKey: "language", value: "zh-CN" },
    });
    assert.equal(pref.statusCode, 200);
    assert.deepEqual(pref.json().preferences, { language: "zh-CN" });

    const closure = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_pref/start-closure",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c007" },
      payload: {},
    });
    assert.equal(closure.statusCode, 200);
    assert.equal(closure.json().status, "CLOSURE_INITIATED");
    assert.ok(closure.json().closureRequestId.startsWith("clr_"));
  });


  it("uses one resolved correlation id for response headers, errors, and published events", async () => {
    const publisher = new InMemoryEventPublisher();
    const app = createApp({ publisher });

    const create = await app.inject({
      method: "POST",
      url: "/api/v1/accounts",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c017" },
      payload: { accountId: "acct_corr_once" },
    });
    const correlationId = String(create.headers["x-correlation-id"]);
    assert.match(correlationId, /^corr-/);
    assert.equal(publisher.envelopes[0]?.correlationId, correlationId);

    const invalid = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_corr_once/freeze",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c018" },
      payload: { reason: "risk" },
    });
    assert.equal(invalid.headers["x-correlation-id"], invalid.json().correlationId);
  });

  it("rejects non-UUID-v7 idempotency keys", async () => {
    const app = createApp();

    const response = await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "not-a-uuid-v7" }, payload: { accountId: "acct_bad_idem" } });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.equal(response.json().details.field, "Idempotency-Key");
  });

  it("returns validation failure body shape for invalid requests", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_missing/freeze",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c008", "x-correlation-id": "corr-validation" },
      payload: { reason: "risk" },
    });

    assert.equal(response.statusCode, 400);
    assert.deepEqual(response.json(), {
      code: "VALIDATION_FAILED",
      message: "operator is required",
      correlationId: "corr-validation",
      details: { field: "operator" },
    });
  });

  it("requires idempotency keys, replays original result, and rejects key reuse with a different body", async () => {
    const app = createApp();

    const missing = await app.inject({ method: "POST", url: "/api/v1/accounts", payload: { accountId: "acct_no_key" } });
    assert.equal(missing.statusCode, 400);
    assert.equal(missing.json().code, "VALIDATION_FAILED");

    const first = await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c009" }, payload: { accountId: "acct_idem" } });
    const replay = await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c009" }, payload: { accountId: "acct_idem" } });
    assert.equal(replay.statusCode, 201);
    assert.deepEqual(replay.json(), first.json());

    const reused = await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c009" }, payload: { accountId: "acct_other" } });
    assert.equal(reused.statusCode, 422);
    assert.equal(reused.json().code, "IDEMPOTENCY_KEY_REUSED");
  });

  it("maps unfreeze and start-closure invalid account states to PRECONDITION_FAILED", async () => {
    const app = createApp();
    await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c013" }, payload: { accountId: "acct_precondition" } });

    const unfreeze = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_precondition/unfreeze",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c014" },
      payload: { reason: "not frozen" },
    });
    assert.equal(unfreeze.statusCode, 412);
    assert.equal(unfreeze.json().code, "PRECONDITION_FAILED");
    assert.equal(unfreeze.json().details.domainCode, "ACCOUNT_NOT_FROZEN");

    const closure = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_precondition/start-closure",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c015" },
      payload: {},
    });
    assert.equal(closure.statusCode, 200);

    const closureAgain = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_precondition/start-closure",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c016" },
      payload: {},
    });
    assert.equal(closureAgain.statusCode, 412);
    assert.equal(closureAgain.json().code, "PRECONDITION_FAILED");
    assert.equal(closureAgain.json().details.domainCode, "CLOSURE_ALREADY_PENDING");
  });

  it("surfaces domain invariant violations as DOMAIN_RULE_VIOLATION", async () => {
    const app = createApp();
    await app.inject({ method: "POST", url: "/api/v1/accounts", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c010" }, payload: { accountId: "acct_domain" } });
    await app.inject({ method: "POST", url: "/api/v1/accounts/acct_domain/freeze", headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c011" }, payload: { reason: "risk", operator: "ops" } });

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/accounts/acct_domain/freeze",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-0284-5c26e8b0c012" },
      payload: { reason: "risk again", operator: "ops" },
    });

    assert.equal(response.statusCode, 422);
    assert.equal(response.json().code, "DOMAIN_RULE_VIOLATION");
    assert.equal(response.json().details.domainCode, "ACCOUNT_NOT_ACTIVE");
  });
});
