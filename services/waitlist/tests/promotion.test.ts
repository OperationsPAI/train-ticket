import assert from "node:assert/strict";
import test from "node:test";
import { InMemoryEventPublisher } from "@trainticket/ts-kit";
import { InMemoryWaitlistRepository, WaitlistApplicationService, type JourneyOrderClient } from "../src/application.js";
import type { CapacityAvailabilityClient, FarePricingClient, OfferManagementClient } from "../src/promotion.js";
import type { WaitlistEntry } from "../src/domain.js";

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
  async createOrder(entry: WaitlistEntry) { return { orderId: `ord-${entry.entryId}`, seatAssignment: { segmentRef: entry.segmentRef } }; }
}

test("WaitlistCapacityFreed promotes highest-priority queued entry", async () => {
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const fare = new StubFarePricing();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, fare, capacity, new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const regular = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", loyaltyTier: "NONE", tripCount: 0, daysBefore: 20, itineraryRef: "itin", paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-regular" });
  const platinum = await service.join({ accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20, itineraryRef: "itin", paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-platinum" });

  const result = await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  assert.equal(result.promoted[0]?.entryId, platinum.waitlistRequestId);
  assert.equal((await service.get(platinum.waitlistRequestId)).status, "MATCHING");
  assert.equal((await service.get(regular.waitlistRequestId)).status, "QUEUED");
  const matchStarted = publisher.findByEventType("WaitlistMatchStarted");
  assert.equal(matchStarted.length, 1);
  assert.deepEqual(matchStarted[0]?.payload, {
    waitlistRequestId: platinum.waitlistRequestId,
    accountId: "acc",
    travelerRef: "t2",
    segmentRef: "seg",
    travelClass: "SECOND",
    matchedCapacityReleaseRef: `capacity-released:${platinum.waitlistRequestId}:2`,
    journeyOrderIdempotencyKey: matchStarted[0]?.payload.journeyOrderIdempotencyKey,
    startedAt: "2026-01-01T00:00:00.000Z",
    status: "MATCHING",
  });
});

test("expired offer releases hold and promotes next queued entry", async () => {
  let current = new Date("2026-01-01T00:00:00.000Z");
  const repository = new InMemoryWaitlistRepository();
  const publisher = new InMemoryEventPublisher();
  const capacity = new StubCapacity();
  const service = new WaitlistApplicationService(repository, publisher, new StubFarePricing(), capacity, new StubJourneyOrder(), () => current, new StubOfferManagement());
  const first = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20, itineraryRef: "itin", paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-first" });
  const second = await service.join({ accountId: "acc", travelerRefs: ["t2"], segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", loyaltyTier: "GOLD", tripCount: 0, daysBefore: 20, itineraryRef: "itin", paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-second" });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  current = new Date("2026-01-01T00:16:00.000Z");
  await service.expireDueOffers();

  assert.equal((await service.get(first.waitlistRequestId)).status, "EXPIRED");
  assert.equal((await service.get(second.waitlistRequestId)).status, "MATCHING");
  assert.deepEqual(capacity.released, [`hold-${first.waitlistRequestId}`]);
  const expired = publisher.findByEventType("WaitlistExpired");
  assert.equal(expired.length, 1);
  assert.deepEqual(expired[0]?.payload, {
    waitlistRequestId: first.waitlistRequestId,
    accountId: "acc",
    travelerRef: "t1",
    segmentRef: "seg",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    expiredAt: "2026-01-01T00:16:00.000Z",
    status: "EXPIRED",
  });
  assert.equal(publisher.findByEventType("WaitlistMatchStarted").length, 2);
});

test("fulfill matching request creates journey order and publishes fulfilled event", async () => {
  const publisher = new InMemoryEventPublisher();
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), publisher, new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20, itineraryRef: "itin", paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-fulfill" });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });
  const order = await service.accept(entry.waitlistRequestId, { paymentMethodRef: "pm-1" });
  assert.equal(order.orderId, `ord-${entry.waitlistRequestId}`);
  assert.equal((await service.get(entry.waitlistRequestId)).status, "FULFILLED");
  assert.deepEqual(publisher.findByEventType("WaitlistFulfilled")[0]?.payload, {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: "acc",
    travelerRef: "t1",
    segmentRef: "seg",
    travelClass: "SECOND",
    journeyOrderRef: `ord-${entry.waitlistRequestId}`,
    fulfilledAt: "2026-01-01T00:00:00.000Z",
    status: "FULFILLED",
  });
});

test("accept does not mutate entry when journey order creation fails", async () => {
  class FailingJourneyOrder implements JourneyOrderClient {
    async createOrder(): Promise<never> { throw new Error("downstream unavailable"); }
  }
  const service = new WaitlistApplicationService(new InMemoryWaitlistRepository(), new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new FailingJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRefs: ["t1"], segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", loyaltyTier: "PLATINUM", tripCount: 0, daysBefore: 20, itineraryRef: "itin", paymentGuaranteeRef: "pay-auth-test", intentFingerprint: "fp-fail" });
  await service.handleCapacityFreed({ segmentRef: "seg", departureDate: "2026-07-20", seatClass: "SECOND", freedSlots: 1 });

  await assert.rejects(() => service.accept(entry.waitlistRequestId), /downstream unavailable/);

  assert.equal((await service.get(entry.waitlistRequestId)).status, "MATCHING");
});

test("archive sweep closes terminal requests while contract GET remains retrievable", async () => {
  const repository = new InMemoryWaitlistRepository();
  const service = new WaitlistApplicationService(repository, new InMemoryEventPublisher(), new StubFarePricing(), new StubCapacity(), new StubJourneyOrder(), () => new Date("2026-01-01T00:00:00.000Z"), new StubOfferManagement());
  const entry = await service.join({ accountId: "acc", travelerRef: "t1", segmentRef: "seg", deadline: "2026-07-20T00:00:00.000Z", travelClass: "SECOND", itineraryRef: "itin-1", paymentGuaranteeRef: "pay-auth-1", intentFingerprint: "t1:seg" });
  await service.cancel(entry.waitlistRequestId);

  const archived = await service.archiveTerminalRequests();

  assert.deepEqual(archived.map((item) => item.status), ["CLOSED"]);
  assert.equal((await service.queueInfo("seg", "2026-07-20", "SECOND", entry.waitlistRequestId)).myPosition, null);
  assert.deepEqual(await service.get(entry.waitlistRequestId), {
    waitlistRequestId: entry.waitlistRequestId,
    accountId: "acc",
    travelerRef: "t1",
    segmentRef: "seg",
    travelClass: "SECOND",
    deadline: "2026-07-20T00:00:00.000Z",
    paymentGuaranteeRef: "pay-auth-1",
    itineraryRef: "itin-1",
    intentFingerprint: "t1:seg",
    status: "CLOSED",
  });
});
