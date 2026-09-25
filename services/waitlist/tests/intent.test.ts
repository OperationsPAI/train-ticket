import assert from "node:assert/strict";
import test from "node:test";
import { DomainError } from "../src/domain.js";
import { HttpWaitlistIntentResolver } from "../src/intent.js";

test("payment-backed waitlist intent restores every traveler and accepted route facts", async () => {
  const originalFetch = globalThis.fetch;
  const calls: string[] = [];
  globalThis.fetch = (async (input: string | URL | Request) => {
    const path = new URL(String(input)).pathname;
    calls.push(path);
    const bodies: Record<string, unknown> = {
      "/api/v1/internal/service-segments/seg-1/supply": {
        segmentRef: "seg-1", scheduledServiceRef: "ss-1", serviceDate: "2026-10-20",
        stopSequenceVersion: "sha256:route-v1", interval: { fromSeq: 1, toSeq: 3 },
        stops: [{ sequence: 0, nodeRef: "n0" }, { sequence: 1, nodeRef: "n1" }, { sequence: 2, nodeRef: "n2" }, { sequence: 3, nodeRef: "n3" }],
      },
      "/api/v1/itineraries/itn-1": {
        itineraryRef: "itn-1", legs: [{ serviceSegmentRef: "seg-1", servicePlanRef: "ss-1", originStopRef: "n1", destinationStopRef: "n3", departureTime: "2026-10-20T03:00:00Z" }],
      },
      "/api/v1/payment-intents/pi-1": { paymentIntentId: "pi-1", businessRef: "ord-source" },
      "/api/v1/journey-orders/ord-source": { orderId: "ord-source", accountId: "acc-1", travelerRefs: ["t1", "t2"], segmentRefs: ["seg-1"], offerId: "off-source" },
      "/api/v1/offers/off-source": { offerId: "off-source", accountId: "acc-1", itineraryRef: "itn-1", fareQuoteRef: "fq-source", passengerMix: { travelers: [{ travelerId: "t1" }, { travelerId: "t2" }] } },
      "/api/v1/fare-quotes/fq-source": { quoteId: "fq-source", breakdown: { seatClassMultiplier: { seatClass: "FIRST_CLASS" } } },
    };
    const body = bodies[path];
    return body ? Response.json(body) : Response.json({ code: "NOT_FOUND" }, { status: 404 });
  }) as typeof fetch;

  try {
    const resolved = await new HttpWaitlistIntentResolver("http://%s").resolve({
      accountId: "acc-1", travelerRefs: ["t1"], segmentRef: "seg-1", itineraryRef: "itn-1",
      paymentGuaranteeRef: "pi-1", departureDate: "2026-10-20",
    }, "corr-1");

    assert.deepEqual(resolved, {
      travelerRefs: ["t1", "t2"], segmentRef: "seg-1", scheduledServiceRef: "ss-1",
      departureDate: "2026-10-20", seatClass: "FIRST_CLASS", interval: { fromSeq: 1, toSeq: 3 },
      stopSequenceVersion: "sha256:route-v1",
    });
    assert.deepEqual(calls, [
      "/api/v1/internal/service-segments/seg-1/supply", "/api/v1/itineraries/itn-1",
      "/api/v1/payment-intents/pi-1", "/api/v1/journey-orders/ord-source",
      "/api/v1/offers/off-source", "/api/v1/fare-quotes/fq-source",
    ]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("payment-backed intent rejects a class different from the guaranteed fare quote", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request) => {
    const path = new URL(String(input)).pathname;
    if (path.includes("service-segments")) return Response.json({ segmentRef: "seg", scheduledServiceRef: "ss", serviceDate: "2026-10-20", stopSequenceVersion: "sha256:v1", interval: { fromSeq: 0, toSeq: 1 }, stops: [{ nodeRef: "a" }, { nodeRef: "b" }] });
    if (path.includes("itineraries")) return Response.json({ itineraryRef: "itn", legs: [{ serviceSegmentRef: "seg", servicePlanRef: "ss", originStopRef: "a", destinationStopRef: "b", departureTime: "2026-10-20T00:00:00Z" }] });
    if (path.includes("payment-intents")) return Response.json({ paymentIntentId: "pi-1", businessRef: "ord-1" });
    if (path.includes("journey-orders")) return Response.json({ orderId: "ord-1", accountId: "acc", travelerRefs: ["t1"], segmentRefs: ["seg"], offerId: "off-1" });
    if (path.includes("offers")) return Response.json({ offerId: "off-1", accountId: "acc", itineraryRef: "itn", fareQuoteRef: "fq-1", passengerMix: { travelers: [{ travelerId: "t1" }] } });
    return Response.json({ quoteId: "fq-1", breakdown: { seatClassMultiplier: { seatClass: "FIRST_CLASS" } } });
  }) as typeof fetch;
  try {
    await assert.rejects(
      () => new HttpWaitlistIntentResolver("http://%s").resolve({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", itineraryRef: "itn", paymentGuaranteeRef: "pi-1", seatClass: "SECOND_CLASS" }),
      (error) => error instanceof DomainError && error.code === "PRECONDITION_FAILED" && /class differs/u.test(error.message),
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
