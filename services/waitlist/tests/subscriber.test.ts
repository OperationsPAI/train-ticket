import assert from "node:assert/strict";
import test from "node:test";
import type { EventEnvelope } from "@trainticket/ts-kit";
import { parseCapacityFreed, tryParseCapacityFreed } from "../src/subscriber.js";

function envelope(eventType: string, payload: Record<string, unknown>): EventEnvelope {
  return {
    eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123",
    eventType,
    occurredAt: "2026-01-01T00:00:00.000Z",
    correlationId: "corr-0194f2e0-7b3e-7610-8284-5c26e8b0c123",
    causationId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c124",
    producer: "capacity-availability",
    schemaVersion: 1,
    payload,
  };
}

test("parses dedicated WaitlistCapacityFreed event", () => {
  const parsed = parseCapacityFreed(envelope("WaitlistCapacityFreed", { segmentRef: "seg-web-2026-08-02-abc", departureDate: "2026-08-02", freedSlots: 2, seatClass: "SECOND" }));

  assert.deepEqual(parsed, {
    segmentRef: "seg-web-2026-08-02-abc",
    departureDate: "2026-08-02",
    freedSlots: 2,
    seatClass: "SECOND",
    capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123",
  });
});

test("parses waitlist-scoped CapacityReleased using service segment fallback", () => {
  const parsed = tryParseCapacityFreed(envelope("CapacityReleased", { serviceSegmentRef: "seg-web-2026-08-02-abc123", quantity: 1 }));

  assert.deepEqual(parsed, {
    segmentRef: "seg-web-2026-08-02-abc123",
    departureDate: "2026-08-02",
    freedSlots: 1,
    seatClass: undefined,
    capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123",
  });
});

test("generic CapacityReleased without segment metadata is ack-skipped", () => {
  assert.equal(tryParseCapacityFreed(envelope("CapacityReleased", { holdId: "hold-1" })), undefined);
});
