import assert from "node:assert/strict";
import test from "node:test";
import { HttpJourneyOrderClient } from "../src/application.js";
import { HttpWaitlistFulfillmentClient } from "../src/fulfillment.js";
import { HttpCapacityAvailabilityClient, HttpFarePricingClient, HttpOfferManagementClient } from "../src/promotion.js";
import { WaitlistEntry } from "../src/domain.js";

function promotedEntry() {
  const entry = WaitlistEntry.create({ entryId: "wl-contract", accountId: "acc", travelerRefs: ["tvl-1", "tvl-2"], segmentRef: "seg-1", departureDate: "2026-07-20", seatClass: "SECOND", priority: { loyaltyTier: "PLATINUM", tripCount: 1 }, itineraryRef: "itn-1", createdAt: new Date("2026-01-01T00:00:00.000Z") });
  entry.offer("off-1", 1, "fq-1", "hold-1", new Date("2026-01-01T00:00:00.000Z"), new Date("2026-01-01T00:15:00.000Z"));
  return entry;
}

function fulfillmentEntry() {
  const entry = WaitlistEntry.create({
    entryId: "wlr-0194f2e0-7b3e-7610-8284-5c26e8b0c300",
    accountId: "acc-1",
    travelerRefs: ["tvl-0194f2e0-7b3e-7610-8284-5c26e8b0c301"],
    segmentRef: "seg-1",
    scheduledServiceRef: "svc-1",
    departureDate: "2026-10-20",
    seatClass: "SECOND_CLASS",
    interval: { fromSeq: 1, toSeq: 4 },
    stopSequenceVersion: "sha256:route-v1",
    deadline: "2026-10-19T00:00:00.000Z",
    priority: {},
    paymentGuarantee: {
      paymentIntentId: "pi-0194f2e0-7b3e-7610-8284-5c26e8b0c302",
      sourceOrderRef: "ord-source",
      payerRef: "acc-1",
      amount: { currency: "CNY", minorUnits: 20_000 },
      sourceStatus: "CREATED",
      sourceExpiresAt: "2026-09-25T00:01:00.000Z",
      authorizedUntil: "2026-10-19T00:00:00.000Z",
      verifiedAt: "2026-09-25T00:00:00.000Z",
      preferredChannel: "ALIPAY",
    },
  });
  entry.beginPromotion([{ capacityReleaseRef: "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c303", slots: 1 }]);
  entry.recordFareQuote("fq-1");
  entry.recordOrderAmount({ currency: "CNY", minorUnits: 10_750 });
  entry.offer("off-1", 1, "fq-1", undefined, new Date("2026-09-25T00:00:00.000Z"), new Date("2026-09-25T00:10:00.000Z"));
  entry.linkJourneyOrder(new Date("2026-09-25T00:01:00.000Z"), "ord-0194f2e0-7b3e-7610-8284-5c26e8b0c304");
  return entry;
}

test("HTTP downstream clients use documented endpoint shapes and idempotency keys without payment calls", async () => {
  const calls: Array<{ url: string; method: string; headers: Headers; body: unknown }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async (input: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers), body: init?.body ? JSON.parse(String(init.body)) : null });
    const url = String(input);
    if (url.endsWith("/api/v1/fare-quotes")) return Response.json({ quoteId: "fq-1" }, { status: 201 });
    if (url.endsWith("/api/v1/offers")) return Response.json({ offerId: "off-1", offerVersion: 1, expiresAt: "2026-01-01T00:10:00.000Z", total: { currency: "CNY", minorUnits: 10_750 } }, { status: 201 });
    if (url.endsWith("/api/v1/capacity-holds")) return Response.json({ holdId: "hold-1" }, { status: 201 });
    if (url.endsWith("/api/v1/capacity-holds/hold-1/release")) return Response.json({ holdId: "hold-1", status: "RELEASED" });
    if (url.endsWith("/api/v1/journey-orders")) return Response.json({ orderId: "ord-1", monetarySummary: { payableTotal: { currency: "CNY", minorUnits: 10_750 } } }, { status: 201 });
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

  const calledPaths = calls.map((call) => [call.method, new URL(call.url).pathname]);
  assert.deepEqual(calledPaths, [
    ["POST", "/api/v1/fare-quotes"],
    ["POST", "/api/v1/offers"],
    ["POST", "/api/v1/capacity-holds"],
    ["POST", "/api/v1/capacity-holds/hold-1/release"],
    ["POST", "/api/v1/journey-orders"],
  ]);
  assert.equal(calledPaths.some(([, path]) => String(path).includes("payment") || String(path).includes("capture")), false);
  assert.ok(calls.every((call) => call.headers.has("Idempotency-Key")));
  assert.deepEqual(calls[0]?.body, { travelerRefs: ["tvl-1", "tvl-2"], channel: "WEB", segmentRefs: ["seg-1"], seatClass: "SECOND_CLASS", productCode: "rail-standard" });
  assert.deepEqual({ ...(calls[2]?.body as Record<string, unknown>), segmentBookingId: undefined }, { segmentRef: "seg-1", travelerRef: "tvl-1", classRef: "SECOND_CLASS", quantity: 2, segmentBookingId: undefined });
  assert.match(String((calls[2]?.body as Record<string, unknown>).segmentBookingId), /^sb-[0-9a-f-]{36}$/u);
  assert.deepEqual(calls[4]?.body, { accountId: "acc", offerId: "off-1", offerVersion: 1, travelerRefs: ["tvl-1", "tvl-2"], segmentRefs: ["seg-1"], journeyDate: "2026-07-20", productCode: "rail-standard" });
});

test("HTTP fulfillment client binds reservation, payment, ticket and cleanup calls to stable keys", async () => {
  const entry = fulfillmentEntry();
  const traveler = entry.fulfillment.travelers[0]!;
  const calls: Array<{ url: string; method: string; headers: Headers; body: unknown }> = [];
  let paymentStatus = "CREATED";
  const fetcher = (async (input: string | URL | Request, init?: RequestInit) => {
    const call = { url: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers), body: init?.body ? JSON.parse(String(init.body)) : null };
    calls.push(call);
    const path = new URL(call.url).pathname;
    if (path === `/api/v1/journey-orders/${entry.journeyOrderRef}` && call.method === "GET") return Response.json({
      orderId: entry.journeyOrderRef, accountId: entry.accountId, offerId: entry.offerId, status: "CREATED", riskClear: true,
      travelerRefs: entry.travelerRefs, segmentRefs: [entry.segmentRef], monetarySummary: { payableTotal: entry.fulfillment.orderAmount },
    });
    if (path.endsWith(`/booking-sagas/by-order/${entry.journeyOrderRef}`)) return Response.json({ sagaId: "saga-1", journeyOrderId: entry.journeyOrderRef, status: "RESERVING" });
    if (path.endsWith(`/segment-bookings/${traveler.segmentBookingId}`)) return Response.json({
      segmentBookingId: traveler.segmentBookingId, journeyOrderId: entry.journeyOrderRef, travelerRef: traveler.travelerRef,
      segmentRef: entry.segmentRef, status: "CONFIRMED", capacityHoldId: "hold-1",
    });
    if (path.endsWith("/request-reservation")) return Response.json({ segmentBookingId: traveler.segmentBookingId, status: "REQUESTED" });
    if (path === "/api/v1/payment-intents" && call.method === "POST") return Response.json({
      paymentIntentId: "pi-new", businessRef: entry.journeyOrderRef, amount: entry.fulfillment.orderAmount, status: "CREATED",
    }, { status: 201 });
    if (path === `/api/v1/payment-intents/by-business/${entry.journeyOrderRef}`) return Response.json({
      paymentIntentId: "pi-new", businessRef: entry.journeyOrderRef, payerRef: entry.accountId, amount: entry.fulfillment.orderAmount, status: "CREATED",
    });
    if (path === "/api/v1/payment-intents/pi-new" && call.method === "GET") return Response.json({
      paymentIntentId: "pi-new", businessRef: entry.journeyOrderRef, payerRef: entry.accountId, amount: entry.fulfillment.orderAmount, status: paymentStatus,
    });
    if (path === "/api/v1/payment-intents/pi-new/capture") return Response.json({ paymentIntentId: "pi-new", status: "CAPTURED", capturedAmount: entry.fulfillment.orderAmount });
    if (path === "/api/v1/payment-intents/pi-new/cancel") {
      paymentStatus = "CAPTURED";
      return Response.json({ code: "DOMAIN_RULE_VIOLATION" }, { status: 422 });
    }
    if (path === "/api/v1/entitlements") return Response.json({ entitlementId: "ent-1", segmentBookingId: traveler.segmentBookingId, journeyOrderId: entry.journeyOrderRef, status: "ISSUED" }, { status: 201 });
    if (path === "/api/v1/capacity-holds/hold-1" && call.method === "GET") return Response.json({ holdId: "hold-1", status: "CONFIRMED" });
    if (path === "/api/v1/capacity-holds/hold-1/release") return Response.json({ holdId: "hold-1", status: "RELEASED" });
    if (path === `/api/v1/journey-orders/${entry.journeyOrderRef}/cancel`) return Response.json({ orderId: entry.journeyOrderRef, status: "CANCELLED" });
    throw new Error(`unexpected ${call.method} ${path}`);
  }) as typeof fetch;

  const client = new HttpWaitlistFulfillmentClient({
    "journey-order": "http://journey-order", "booking-orchestration": "http://booking-orchestration",
    payment: "http://payment", "entitlement-ticketing": "http://entitlement-ticketing", "capacity-availability": "http://capacity-availability",
  }, fetcher);
  assert.equal((await client.getOrder(entry.journeyOrderRef!)).amount.minorUnits, 10_750);
  assert.equal((await client.findSaga(entry.journeyOrderRef!))?.sagaId, "saga-1");
  assert.equal((await client.getReservation(traveler))?.capacityHoldId, "hold-1");
  await client.requestReservation(entry, traveler, "saga-1");
  const created = await client.createPayment(entry);
  assert.equal(created.payerRef, entry.accountId, "create response inherits the known payer because Payment omits it");
  assert.equal((await client.findPayment(entry.journeyOrderRef!))?.paymentIntentId, "pi-new");
  entry.recordPayment("pi-new", "CREATED");
  await client.getPayment("pi-new");
  await client.capturePayment(entry);
  await client.issueEntitlement(entry, traveler);
  await client.releaseCapacity(traveler, "hold-1");
  assert.equal((await client.cancelPayment(entry, "WAITLIST_TEST"))?.status, "CAPTURED");
  await client.cancelOrder(entry, "WAITLIST_TEST");

  const postCalls = calls.filter((call) => call.method === "POST");
  assert.deepEqual(postCalls.map((call) => new URL(call.url).pathname), [
    "/api/v1/internal/booking-sagas/saga-1/request-reservation",
    "/api/v1/payment-intents",
    "/api/v1/payment-intents/pi-new/capture",
    "/api/v1/entitlements",
    "/api/v1/capacity-holds/hold-1/release",
    "/api/v1/payment-intents/pi-new/cancel",
    `/api/v1/journey-orders/${entry.journeyOrderRef}/cancel`,
  ]);
  assert.deepEqual(postCalls.map((call) => call.headers.get("idempotency-key")), [
    traveler.reservationIdempotencyKey,
    entry.fulfillment.paymentCreateIdempotencyKey,
    entry.fulfillment.paymentCaptureIdempotencyKey,
    traveler.entitlementIdempotencyKey,
    traveler.capacityReleaseIdempotencyKey,
    entry.fulfillment.paymentCancelIdempotencyKey,
    entry.fulfillment.orderCancelIdempotencyKey,
  ]);
  assert.deepEqual(postCalls[0]?.body, {
    segmentRef: entry.segmentRef, travelerRef: traveler.travelerRef, segmentBookingId: traveler.segmentBookingId,
    classRef: entry.seatClass, scheduledServiceRef: entry.scheduledServiceRef, serviceDate: entry.departureDate,
  });
  assert.deepEqual(postCalls[1]?.body, { businessRef: entry.journeyOrderRef, purpose: "purchase", amount: entry.fulfillment.orderAmount, payerRef: entry.accountId, preferredChannel: "ALIPAY" });
  assert.deepEqual(postCalls[2]?.body, { channelRef: { channel: "ALIPAY" } });
  assert.deepEqual(postCalls[3]?.body, { segmentBookingId: traveler.segmentBookingId, journeyOrderId: entry.journeyOrderRef, travelerRef: traveler.travelerRef, segmentRef: entry.segmentRef, issuePurpose: "INITIAL" });
});

test("capacity cleanup retries when a recorded hold is temporarily unreadable", async () => {
  const entry = fulfillmentEntry();
  const traveler = entry.fulfillment.travelers[0]!;
  const client = new HttpWaitlistFulfillmentClient({ "capacity-availability": "http://capacity-availability" }, (async () => (
    Response.json({ code: "NOT_FOUND" }, { status: 404 })
  )) as typeof fetch);

  await assert.rejects(() => client.releaseCapacity(traveler, "hold-not-visible"), /status 404/u);
});
