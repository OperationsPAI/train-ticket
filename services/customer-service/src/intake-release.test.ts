import assert from "node:assert/strict";
import { describe, it } from "node:test";

// The release's channel statement reaches the intake through the process and the
// intake reads it once, so the variable has to be set before the app is imported.
process.env.CUSTOMER_SERVICE_INTAKE_CHANNELS = "WEB,PHONE";

const { createApp } = await import("./app.js");

const openBody = {
  requesterRef: "tvl-0194f2e0-7b3e-7610-8284-5c26e8b101",
  classification: "POST_SALES_HELP",
  priority: "NORMAL",
  description: "Help me with my order",
  businessReferences: { journeyOrderId: "ord-0194f2e0-7b3e-7610-8284-5c26e8b102" },
};

function idempotencyKey(sequence: number): string {
  return `018f2e00-7b3e-7610-8284-${sequence.toString(16).padStart(12, "0")}`;
}

describe("customer-service intake against a narrowed channel statement", () => {
  it("refuses a channel the statement does not name, before a case is stored", async () => {
    const response = await createApp().inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": idempotencyKey(0x11) },
      payload: { ...openBody, channel: "APP" },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.equal(response.json().details.field, "channel");
  });

  it("opens a case on a channel the statement names", async () => {
    const response = await createApp().inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": idempotencyKey(0x12) },
      payload: { ...openBody, channel: "PHONE" },
    });

    assert.equal(response.statusCode, 201);
  });
});
