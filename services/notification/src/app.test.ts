import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { InMemoryIdempotencyStore, createApp, handleIdempotentPost } from "./index.js";

describe("notification service HTTP contract", () => {
  it("serves health, liveness, readiness, and metadata with request correlation headers", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/readyz",
      headers: {
        "x-request-id": "req-notification-1",
        "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["x-request-id"], "req-notification-1");
    assert.equal(response.headers["x-correlation-id"], "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.deepEqual(response.json(), { status: "ok", probe: "ready" });

    const healthz = await app.inject("/healthz");
    assert.equal(healthz.statusCode, 200);
    assert.deepEqual(healthz.json(), { status: "ok", probe: "live" });

    const health = await app.inject("/health");
    assert.equal(health.statusCode, 200);
    assert.equal(health.json().status, "ok");
    assert.equal(health.json().service.serviceId, "notification");

    assert.deepEqual((await app.inject("/live")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/livez")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready" });

    const metadata = await app.inject("/metadata");
    assert.equal(metadata.statusCode, 200);
    assert.equal(metadata.json().service.serviceId, "notification");
    assert.deepEqual(metadata.json().observability, { tracing: "opt-in", default: "noop" });
  });

  it("generates request and correlation ids when headers are absent", async () => {
    const app = createApp();

    const response = await app.inject("/livez");

    assert.equal(response.statusCode, 200);
    assert.equal(typeof response.headers["x-request-id"], "string");
    assert.notEqual(response.headers["x-request-id"], "");
    assert.match(String(response.headers["x-correlation-id"]), /^corr-/);
  });

  it("returns the canonical error body for missing routes", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/notifications",
      headers: { "x-request-id": "req-notification-404", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
    });

    assert.equal(response.statusCode, 404);
    assert.deepEqual(response.json(), {
      code: "NOT_FOUND",
      message: "Route GET /api/v1/notifications was not found",
      correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      details: {},
    });
  });

  it("has no business HTTP endpoints beyond the notification contract health surface", async () => {
    const app = createApp();

    const response = await app.inject({ method: "POST", url: "/api/v1/notifications", payload: {} });

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().code, "NOT_FOUND");
  });

  it("returns canonical validation errors and replays idempotent POST results for state-changing routes", async () => {
    const app = createApp();
    const store = new InMemoryIdempotencyStore();
    let calls = 0;

    app.post("/_test/state-change", {
      schema: {
        body: {
          type: "object",
          required: ["name"],
          properties: { name: { type: "string" } },
        },
      },
    }, async (request, reply) => handleIdempotentPost(request, reply, store, async () => {
      calls += 1;
      return { statusCode: 201, payload: { id: `created-${calls}` } };
    }));

    const invalid = await app.inject({
      method: "POST",
      url: "/_test/state-change",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c222", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
      payload: {},
    });
    assert.equal(invalid.statusCode, 400);
    assert.equal(invalid.json().code, "VALIDATION_FAILED");
    assert.equal(invalid.json().correlationId, "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222");
    assert.deepEqual(invalid.json().details, {});

    const first = await app.inject({
      method: "POST",
      url: "/_test/state-change",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c222", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
      payload: { name: "Alice" },
    });
    const replay = await app.inject({
      method: "POST",
      url: "/_test/state-change",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c222", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
      payload: { name: "Alice" },
    });
    const reused = await app.inject({
      method: "POST",
      url: "/_test/state-change",
      headers: { "idempotency-key": "0194f2e0-7b3e-7610-8284-5c26e8b0c222", "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" },
      payload: { name: "Bob" },
    });

    assert.equal(first.statusCode, 201);
    assert.equal(replay.statusCode, 201);
    assert.deepEqual(replay.json(), first.json());
    assert.equal(calls, 1);
    assert.equal(reused.statusCode, 422);
    assert.equal(reused.json().code, "IDEMPOTENCY_KEY_REUSED");
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
        "x-request-id": "req-notification-trace",
        "x-correlation-id": "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(seenRequests, [{ requestId: "req-notification-trace", correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222" }]);
    assert.deepEqual(endedSpans, [
      {
        method: "GET",
        url: "/health",
        statusCode: 200,
        requestId: "req-notification-trace",
        correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c222",
      },
    ]);

    assert.equal((await createApp().inject("/health")).statusCode, 200);
  });
});
