import assert from "node:assert/strict";
import test from "node:test";
import { HttpJourneyOrderClient } from "../src/application.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient } from "../src/promotion.js";
import { WaitlistEntry } from "../src/domain.js";

function promotedEntry() {
  const entry = WaitlistEntry.create({ entryId: "wl-contract", accountId: "acc", travelerRefs: ["tvl-1", "tvl-2"], segmentRef: "seg-1", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "PLATINUM", tripCount: 1 }, itineraryRef: "itn-1", deadline: new Date("2026-07-20T00:00:00.000Z"), paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-test", createdAt: new Date("2026-01-01T00:00:00.000Z") });
  entry.offer("off-1", 1, "fq-1", "hold-1", new Date("2026-01-01T00:00:00.000Z"), new Date("2026-01-01T00:15:00.000Z"));
  return entry;
}

test("HTTP downstream clients use documented endpoint shapes and idempotency keys", async () => {
  const calls: Array<{ url: string; method: string; headers: Headers; body: unknown }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers), body: init?.body ? JSON.parse(String(init.body)) : null });
    const url = String(input);
    if (url.endsWith("/api/v1/fare-quotes")) return Response.json({ quoteId: "fq-1" }, { status: 201 });
    if (url.endsWith("/api/v1/offers")) return Response.json({ offerId: "off-1", offerVersion: 1 }, { status: 201 });
    if (url.endsWith("/api/v1/capacity-holds")) return Response.json({ holdId: "hold-1" }, { status: 201 });
    if (url.endsWith("/api/v1/capacity-holds/hold-1/release")) return Response.json({ holdId: "hold-1", status: "RELEASED" });
    if (url.endsWith("/api/v1/journey-orders")) return Response.json({ orderId: "ord-1" }, { status: 201 });
    throw new Error(`unexpected URL ${url}`);
  }) as typeof fetch;

  try {
    const entry = promotedEntry();
    await new HttpFarePricingClient("http://fare-pricing", "WEB").quote(entry);
    await new HttpOfferManagementClient("http://offer-management", "WEB").createOffer(entry, "fq-1");
    await new HttpCapacityAvailabilityClient("http://capacity-availability").hold(entry, "fq-1");
    await new HttpCapacityAvailabilityClient("http://capacity-availability").releaseHold(entry, "hold-1");
    await new HttpJourneyOrderClient("http://journey-order").createOrder(entry);
  } finally {
    globalThis.fetch = originalFetch;
  }

  assert.deepEqual(calls.map((call) => [call.method, new URL(call.url).pathname]), [
    ["POST", "/api/v1/fare-quotes"],
    ["POST", "/api/v1/offers"],
    ["POST", "/api/v1/capacity-holds"],
    ["POST", "/api/v1/capacity-holds/hold-1/release"],
    ["POST", "/api/v1/journey-orders"],
  ]);
  assert.ok(calls.every((call) => call.headers.has("Idempotency-Key")));
  assert.deepEqual(calls[0]?.body, { travelerRefs: ["tvl-1", "tvl-2"], channel: "WEB", segmentRefs: ["seg-1"], productCode: "rail-standard" });
  assert.deepEqual({ ...(calls[2]?.body as Record<string, unknown>), segmentBookingId: undefined }, { segmentRef: "seg-1", travelerRef: "tvl-1", classRef: "SECOND", quantity: 2, segmentBookingId: undefined });
  assert.match(String((calls[2]?.body as Record<string, unknown>).segmentBookingId), /^sb-[0-9a-f-]{36}$/u);
  assert.deepEqual(calls[4]?.body, { accountId: "acc", offerId: "off-1", offerVersion: 1, travelerRefs: ["tvl-1", "tvl-2"], segmentRefs: ["seg-1"], journeyDate: "2026-07-20", productCode: "rail-standard" });
});
