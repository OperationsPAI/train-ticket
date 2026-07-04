import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp } from "./index.js";

describe("ancillary-service operational foundation", () => {
  it("serves health, liveness, and readiness with request correlation headers", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/readyz",
      headers: {
        "x-request-id": "req-ancillary-service-1",
        "x-correlation-id": "corr-ancillary-service-1",
      },
    });

    assert.equal(response.statusCode, 200);
    assert.equal(response.headers["x-request-id"], "req-ancillary-service-1");
    assert.equal(response.headers["x-correlation-id"], "corr-ancillary-service-1");
    assert.equal(response.json().status, "ok");
    assert.equal(response.json().service.serviceId, "ancillary-service");

    assert.equal((await app.inject("/health")).statusCode, 200);
    assert.equal((await app.inject("/livez")).statusCode, 200);
  });

  it("returns the standard error envelope for missing routes", async () => {
    const app = createApp();

    const response = await app.inject({
      method: "GET",
      url: "/missing",
      headers: { "x-request-id": "req-ancillary-service-404" },
    });

    assert.equal(response.statusCode, 404);
    assert.deepEqual(response.json(), {
      error: {
        code: "NOT_FOUND",
        message: "Route GET /missing was not found",
        requestId: "req-ancillary-service-404",
        correlationId: "req-ancillary-service-404",
      },
    });
  });
});
