import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { NOTIFICATION_OUTBOX_RELAY_OPTIONS } from "./adapters/storage/runtime.js";

describe("notification outbox throughput", () => {
  it("raises the bounded publish ceiling while keeping retention ahead of publication", () => {
    const publishPerSecond = NOTIFICATION_OUTBOX_RELAY_OPTIONS.batchSize
      * 1_000 / NOTIFICATION_OUTBOX_RELAY_OPTIONS.pollIntervalMs;
    const cleanupPerSecond = 5_000
      * 1_000 / (20 * NOTIFICATION_OUTBOX_RELAY_OPTIONS.pollIntervalMs);

    assert.equal(NOTIFICATION_OUTBOX_RELAY_OPTIONS.batchSize, 200);
    assert.equal(publishPerSecond, 4_000);
    assert.equal(cleanupPerSecond, 5_000);
    assert.ok(cleanupPerSecond >= publishPerSecond);
  });
});
