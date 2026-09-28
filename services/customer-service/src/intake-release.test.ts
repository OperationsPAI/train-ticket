import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createApp } from "./index.js";
import { PLATFORM_CHANNELS, readIntakeSettings } from "./intake-rules.js";

/**
 * Pins both sides of the statement the service ships beside its code: a channel
 * the settings name still opens a case, and one they leave out is refused at the
 * HTTP surface before anything is stored.
 */

const settings = readIntakeSettings();
const answered = settings.channels[0];
const leftOut = PLATFORM_CHANNELS.find((channel) => !settings.channels.includes(channel));

const openBody = {
  requesterRef: "tvl-0194f2e0-7b3e-7610-8284-5c26e8b201",
  classification: "POST_SALES_HELP",
  priority: "NORMAL",
  description: "Help me with my order",
  businessReferences: { journeyOrderId: "ord-0194f2e0-7b3e-7610-8284-5c26e8b202" },
};

function idempotencyKey(sequence: number): string {
  return `018f2e00-7b3e-7610-8284-${sequence.toString(16).padStart(12, "0")}`;
}

describe("customer-service intake against the settings the service ships", () => {
  it("opens a case on a channel the settings name", async () => {
    assert.ok(answered);

    const response = await createApp().inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": idempotencyKey(0x21) },
      payload: { ...openBody, channel: answered },
    });

    assert.equal(response.statusCode, 201);
  });

  it("refuses a channel the settings leave out, before a case is stored", async () => {
    assert.ok(leftOut, "the desk's settings narrow the platform's vocabulary");

    const response = await createApp().inject({
      method: "POST",
      url: "/api/v1/support-cases",
      headers: { "idempotency-key": idempotencyKey(0x22) },
      payload: { ...openBody, channel: leftOut },
    });

    assert.equal(response.statusCode, 400);
    assert.equal(response.json().code, "VALIDATION_FAILED");
    assert.equal(response.json().details.field, "channel");
  });
});
