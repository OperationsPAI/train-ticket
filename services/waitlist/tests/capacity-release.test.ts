import assert from "node:assert/strict";
import test from "node:test";
import type { EventEnvelope } from "@trainticket/ts-kit";
import { HttpCapacityReleaseResolver } from "../src/capacity-release.js";
import { parseCapacityFreed, tryParseCapacityFreed } from "../src/subscriber.js";

test("generic CapacityReleased resolves class, date and interval from durable booking facts", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request) => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith("/segment-bookings/sb-1")) return Response.json({ segmentBookingId: "sb-1", segmentRef: "seg-1", travelerRef: "t1", classRef: "SECOND_CLASS", scheduledServiceRef: "ss-1", serviceDate: "2026-10-20" });
    if (path.endsWith("/service-segments/seg-1/supply")) return Response.json({ segmentRef: "seg-1", scheduledServiceRef: "ss-1", serviceDate: "2026-10-20", stopSequenceVersion: "sha256:route-v1", interval: { fromSeq: 0, toSeq: 4 } });
    return Response.json({ code: "NOT_FOUND" }, { status: 404 });
  }) as typeof fetch;
  const envelope = {
    eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123", eventType: "CapacityReleased", schemaVersion: 1,
    producer: "capacity-availability", correlationId: "corr-1", occurredAt: "2026-09-25T00:00:00Z",
    payload: { interval: { fromSeq: 1, toSeq: 3 }, references: { segmentBookingRef: "sb-1", travelerRef: "t1" } },
  } as EventEnvelope;
  try {
    assert.deepEqual(await new HttpCapacityReleaseResolver("http://%s").resolve(envelope), {
      segmentRef: "seg-1", scheduledServiceRef: "ss-1", departureDate: "2026-10-20",
      seatClass: "SECOND_CLASS", interval: { fromSeq: 1, toSeq: 3 }, stopSequenceVersion: "sha256:route-v1",
      freedSlots: 1, capacityReleaseRef: envelope.eventId,
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("WaitlistCapacityFreed requires all match dimensions before promotion", () => {
  const full = {
    eventId: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c124", eventType: "WaitlistCapacityFreed", schemaVersion: 1,
    producer: "capacity-availability", correlationId: "corr-1", occurredAt: "2026-09-25T00:00:00Z",
    payload: { segmentRef: "seg-1", scheduledServiceRef: "ss-1", departureDate: "2026-10-20", classRef: "SECOND", interval: { fromSeq: 0, toSeq: 2 }, stopSequenceVersion: "sha256:v1", freedSlots: 2, intentFingerprint: "intent-1", capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c125" },
  } as EventEnvelope;
  assert.deepEqual(parseCapacityFreed(full), {
    segmentRef: "seg-1", scheduledServiceRef: "ss-1", departureDate: "2026-10-20", seatClass: "SECOND_CLASS",
    interval: { fromSeq: 0, toSeq: 2 }, stopSequenceVersion: "sha256:v1", freedSlots: 2,
    intentFingerprint: "intent-1", capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c125",
  });
  assert.equal(tryParseCapacityFreed({ ...full, payload: { ...full.payload, interval: undefined } }), undefined);
});
