import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";
import { HttpJourneyOrderClient, InMemoryWaitlistRepository, WaitlistApplicationService } from "../src/application.js";
import { HttpFarePricingClient, HttpOfferManagementClient, type CapacityAvailabilityClient, type FarePricingClient, type JourneyOrderClient, type OfferManagementClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";

const CAPACITY_RELEASE_REF = "evt-0194f2e0-7b3e-7610-8284-5c26e8b0c123";

class StubFarePricing implements FarePricingClient {
  public calls: string[] = [];
  async quote(entry: WaitlistEntry) { this.calls.push(entry.entryId); return { fareQuoteId: `fq-${entry.entryId}` }; }
}

class StubOfferManagement implements OfferManagementClient {
  async createOffer(entry: WaitlistEntry) { return { offerId: `off-${entry.entryId}`, offerVersion: 1 }; }
}

class StubCapacity implements CapacityAvailabilityClient {
  public holds: string[] = [];
  public released: string[] = [];
  async hold(entry: WaitlistEntry) { const capacityHoldId = `hold-${entry.entryId}`; this.holds.push(capacityHoldId); return { capacityHoldId }; }
  async releaseHold(_entry: WaitlistEntry, capacityHoldId: string) { this.released.push(capacityHoldId); }
}

class StubJourneyOrder implements JourneyOrderClient {
  public orders: string[] = [];
  async createOrder(entry: WaitlistEntry) {
    this.orders.push(entry.entryId);
    return { orderId: `ord-${entry.entryId}`, seatAssignment: { segmentRef: entry.segmentRef } };
  }
}

test("WaitlistCapacityFreed starts highest-priority queued entry and fulfills on journey-order confirmation", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const fare = new StubFarePricing();
  const capacity = new StubCapacity();
  const journeyOrder = new StubJourneyOrder();
  const service = new WaitlistApplicationService(repository, publisher, fare, capacity, journeyOrder, () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const regular = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "NONE", tripCount: 0, daysBefore: 20 });
  const platinum = await service.join({ accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });

  const result = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, capacityReleaseRef: CAPACITY_RELEASE_REF });

  assert.equal(result.promoted[0]?.waitlistRequestId, platinum.waitlistRequestId);
  assert.equal(result.promoted[0]?.status, "MATCHING");
  assert.equal(result.promoted[0]?.journeyOrderRef, `ord-${platinum.waitlistRequestId}`);
  assert.equal((await service.get(platinum.waitlistRequestId)).status, "MATCHING");
  assert.equal((await service.get(platinum.waitlistRequestId)).journeyOrderRef, `ord-${platinum.waitlistRequestId}`);
  assert.equal((await service.get(regular.waitlistRequestId)).status, "QUEUED");
  assert.deepEqual(journeyOrder.orders, [platinum.waitlistRequestId]);
  assert.deepEqual(capacity.holds, []);
  assert.equal(publisher.findByEventType("WaitlistHoldAuthorized").length, 0);
  const matchStarted = publisher.findByEventType("WaitlistMatchStarted")[0];
  assert.equal(matchStarted?.payload.matchedCapacityReleaseRef, CAPACITY_RELEASE_REF);
  assert.equal(publisher.findByEventType("WaitlistMatchStarted").length, 1);
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 0);

  await service.handleJourneyOrderConfirmed(`ord-${platinum.waitlistRequestId}`);

  assert.equal((await service.get(platinum.waitlistRequestId)).status, "FULFILLED");
  const fulfilled = publisher.findByEventType("WaitlistFulfilled")[0];
  assert.equal(fulfilled?.payload.journeyOrderRef, `ord-${platinum.waitlistRequestId}`);
  assert.equal(fulfilled?.payload.status, "FULFILLED");
});

test("no payment-service calls are made while fulfilling a matched waitlist entry", async () => {
  const originalFetch = globalThis.fetch;
  const calls: string[] = [];
  globalThis.fetch = (async (input: string | URL | Request) => {
    const url = String(input);
    calls.push(url);
    if (url.endsWith("/api/v1/fare-quotes")) return Response.json({ quoteId: "fq-http" }, { status: 201 });
    if (url.endsWith("/api/v1/offers")) return Response.json({ offerId: "off-http", offerVersion: 1 }, { status: 201 });
    if (url.endsWith("/api/v1/journey-orders")) return Response.json({ orderId: "ord-http" }, { status: 201 });
    return Response.json({ error: "unexpected" }, { status: 500 });
  }) as typeof fetch;
  try {
    const service = new WaitlistApplicationService(
      new InMemoryWaitlistRepository(),
      new InMemoryEventPublisher(),
      new HttpFarePricingClient("http://fare-pricing", "WEB"),
      new StubCapacity(),
      new HttpJourneyOrderClient("http://journey-order"),
      () => new Date("2026-01-01T00:00:00.000Z"),
      new HttpOfferManagementClient("http://offer-management", "WEB"),
    );
    const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20, paymentGuaranteeRef: "pay-auth-existing" });

    await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, capacityReleaseRef: CAPACITY_RELEASE_REF });

    assert.equal((await service.get(entry.waitlistRequestId)).journeyOrderRef, "ord-http");
    assert.deepEqual(calls.map((url) => new URL(url).pathname), ["/api/v1/fare-quotes", "/api/v1/offers", "/api/v1/journey-orders"]);
    assert.deepEqual(calls.filter((url) => url.includes("payment") || url.includes("payment-intents") || url.includes("/capture")), []);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("deadline-passed queued request expires during expiry sweep", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20, deadline: "2025-12-31T23:59:59.000Z" });

  const expired = await service.expireDueOffers();

  assert.equal(expired[0]?.entryId, entry.waitlistRequestId);
  assert.equal((await service.get(entry.waitlistRequestId)).status, "EXPIRED");
  assert.equal(publisher.findByEventType("WaitlistExpired").length, 1);
});

test("capacity freed without a CapacityReleased eventId is rejected before publishing a match fact", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });

  await assert.rejects(
    () => service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, capacityReleaseRef: "" }),
    /capacityReleaseRef must be the consumed CapacityReleased eventId/u,
  );

  assert.equal(publisher.findByEventType("WaitlistMatchStarted").length, 0);
});

test("capacity freed records order ref and confirmation publishes fulfillment", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });
  const stored = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, capacityReleaseRef: CAPACITY_RELEASE_REF });
  const matching = await service.get(entry.waitlistRequestId);

  assert.equal(stored.promoted[0]?.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
  assert.equal(matching.status, "MATCHING");
  assert.equal(matching.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 0);

  await service.handleJourneyOrderConfirmed(`ord-${entry.waitlistRequestId}`);
  const fulfilled = await service.get(entry.waitlistRequestId);
  assert.equal(fulfilled.status, "FULFILLED");
  assert.equal(fulfilled.journeyOrderRef, `ord-${entry.waitlistRequestId}`);
  assert.equal(publisher.findByEventType("WaitlistFulfilled").length, 1);
});

test("journey order creation failure leaves matched entry in MATCHING for retry or cancellation", async () => {
  class FailingJourneyOrder implements JourneyOrderClient {
    async createOrder(): Promise<never> { throw new Error("downstream unavailable"); }
  }
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new FailingJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20 });

  await assert.rejects(() => service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1, capacityReleaseRef: CAPACITY_RELEASE_REF }), /downstream unavailable/);

  assert.equal((await service.get(entry.waitlistRequestId)).status, "MATCHING");
  assert.equal((await service.get(entry.waitlistRequestId)).journeyOrderRef, undefined);
});
