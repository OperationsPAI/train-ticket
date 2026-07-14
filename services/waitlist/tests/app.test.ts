import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher, uuidV7 } from "@trainticket/ts-kit";
import { createApp } from "../src/app.js";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";

test("health endpoint returns 200", async () => {
  const app = createApp();
  const response = await app.inject({ method: "GET", url: "/health" });
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().status, "ok");
  await app.close();
});

class StubFarePricing implements FarePricingClient {
  async quote(entry: WaitlistEntry) { return { fareQuoteId: `fq-${entry.entryId}` }; }
}

class StubOfferManagement implements OfferManagementClient {
  async createOffer(entry: WaitlistEntry) { return { offerId: `off-${entry.entryId}`, offerVersion: 1 }; }
}

class StubCapacity implements CapacityAvailabilityClient {
  async hold(entry: WaitlistEntry) { return { capacityHoldId: `hold-${entry.entryId}` }; }
  async releaseHold() { return undefined; }
}

class StubJourneyOrder implements JourneyOrderClient {
  async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: null }; }
}

test("contract cancel route returns documented response", async () => {
  const repository = new InMemoryWaitlistRepository();
  const app = createApp({ repository, now: () => new Date("2026-01-01T00:00:00.000Z") });
  const createResponse = await app.inject({
    method: "POST",
    url: "/api/v1/waitlist-requests",
    headers: { "idempotency-key": uuidV7() },
    payload: { accountId: "acc-1", travelerRef: "tvl-1", segmentRef: "seg-1", deadline: "2026-07-20T23:59:59.000Z", paymentGuaranteeRef: "pay-auth-1", itineraryRef: "itin-1", intentFingerprint: "intent-1" },
  });
  const waitlistRequestId = createResponse.json().waitlistRequestId;

  const cancelResponse = await app.inject({
    method: "POST",
    url: `/api/v1/waitlist-requests/${waitlistRequestId}/cancel`,
    headers: { "idempotency-key": uuidV7() },
    payload: { reason: "user-requested" },
  });

  assert.equal(cancelResponse.statusCode, 200);
  assert.deepEqual(cancelResponse.json(), { waitlistRequestId, status: "CANCELLED", cancelledAt: "2026-01-01T00:00:00.000Z" });
  await app.close();
});

test("public accept route is not exposed", async () => {
  const app = createApp();
  const response = await app.inject({ method: "POST", url: "/api/v1/waitlist/entries/wlr-1/accept", payload: {} });
  assert.equal(response.statusCode, 404);
  await app.close();
});

test("contract GET returns CLOSED request resource after archival sweep", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const app = createApp({ repository, publisher, farePricing: new StubFarePricing(), capacityAvailability: new StubCapacity(), journeyOrder: new StubJourneyOrder(), offerManagement: new StubOfferManagement(), now: () => new Date("2026-01-01T00:00:00.000Z") });
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc-1", travelerRefs: ["tvl-1"], segmentRef: "seg-1", departureDate: "2026-07-20", seatClass: "SECOND", itineraryRef: "itin-1" });
  await service.handleCapacityFreed({ segmentRef: "seg-1", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  await service.accept(entry.entryId);
  await service.sweepClosed();

  const queue = await repository.queueFor("seg-1", "2026-07-20", "SECOND");
  assert.equal(queue.some((snapshot) => snapshot.entryId === entry.entryId), false);

  const response = await app.inject({ method: "GET", url: `/api/v1/waitlist-requests/${entry.entryId}` });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(Object.keys(response.json()).sort(), ["accountId", "deadline", "intentFingerprint", "itineraryRef", "journeyOrderRef", "paymentGuaranteeRef", "segmentRef", "status", "travelClass", "travelerRef", "waitlistRequestId"].sort());
  assert.equal(response.json().waitlistRequestId, entry.entryId);
  assert.equal(response.json().status, "CLOSED");
  await app.close();
});
