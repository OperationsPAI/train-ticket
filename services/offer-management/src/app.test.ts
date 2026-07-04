import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp } from "./index.js";

describe("offer-management service operational foundation", () => {
  it("serves health, liveness, readiness, and metadata with request correlation headers", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/readyz",
      headers: {
        "x-request-id": "req-offer-management-1",
        "x-correlation-id": "corr-offer-management-1",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["x-request-id"], "req-offer-management-1");
    assert.equal(response.headers["x-correlation-id"], "corr-offer-management-1");
    assert.deepEqual(response.json(), { status: "ok", probe: "ready" });

    const health = await app.inject("/health");
    assert.equal(health.statusCode, 200);
    assert.equal(health.json().status, "ok");
    assert.equal(health.json().service.serviceId, "offer-management");

    assert.deepEqual((await app.inject("/live")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/livez")).json(), { status: "ok", probe: "live" });
    assert.deepEqual((await app.inject("/ready")).json(), { status: "ok", probe: "ready" });

    const metadata = await app.inject("/metadata");
    assert.equal(metadata.statusCode, 200);
    assert.equal(metadata.json().service.serviceId, "offer-management");
    assert.deepEqual(metadata.json().observability, { tracing: "opt-in", default: "noop" });
  });

  it("generates request and correlation ids when headers are absent", async () => {
    const app = createApp();

    const response = await app.inject("/livez");

    assert.equal(response.statusCode, 200);
    assert.equal(typeof response.headers["x-request-id"], "string");
    assert.notEqual(response.headers["x-request-id"], "");
    assert.equal(response.headers["x-correlation-id"], response.headers["x-request-id"]);
  });

  it("returns the standard error envelope for missing routes", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/missing",
      headers: { "x-request-id": "req-offer-management-404" },
    });

    assert.equal(response.statusCode, 404);
    assert.deepEqual(response.json(), {
      error: {
        code: "NOT_FOUND",
        message: "Route GET /missing was not found",
        requestId: "req-offer-management-404",
        correlationId: "req-offer-management-404",
      },
    });
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
        "x-request-id": "req-offer-management-trace",
        "x-correlation-id": "corr-offer-management-trace",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(seenRequests, [{ requestId: "req-offer-management-trace", correlationId: "corr-offer-management-trace" }]);
    assert.deepEqual(endedSpans, [
      {
        method: "GET",
        url: "/health",
        statusCode: 200,
        requestId: "req-offer-management-trace",
        correlationId: "corr-offer-management-trace",
      },
    ]);

    assert.equal((await createApp().inject("/health")).statusCode, 200);
  });
});
